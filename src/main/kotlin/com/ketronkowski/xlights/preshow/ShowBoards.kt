package com.ketronkowski.xlights.preshow

import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** A show WLED board from the xLights layout. */
internal data class ShowBoard(val name: String, val host: String)

/** The WLED boards in the layout, minus FPP and the boards the config ignores. */
internal fun showBoardsOf(ctx: CheckContext, ignore: List<String>): List<ShowBoard> {
    val fppIp = ctx.fppHost?.let { resolveHost(it) }
    return ctx.controllers
        .filter { !it.isFpp && !it.ipAddress.isNullOrBlank() }
        .filter { c -> ignore.none { it.equals(c.name, ignoreCase = true) } }
        .filter { c -> fppIp == null || resolveHost(c.ipAddress!!) != fppIp }
        .map { ShowBoard(it.name, it.ipAddress!!) }
}

internal fun resolveHost(host: String): String =
    runCatching { InetAddress.getByName(host).hostAddress }.getOrDefault(host)

/**
 * Whether the show boards are switched on. WLED's relay switches each board's 12 V
 * pixel supplies, so this decides which standby current is normal: about 0.8 A with
 * them off (before FPP's 4 PM power-on) and about 4.4 A with them on at Black.
 */
data class BoardPower(val on: Int, val off: Int, val unreachable: Int, val offNames: List<String> = emptyList()) {
    val answering get() = on + off
    val allOn get() = answering > 0 && off == 0
    val allOff get() = answering > 0 && on == 0
}

@Component
class ShowBoards(
    private val http: JsonHttp,
    private val props: PreShowProperties,
) {
    /** One GET of /json/state per board, in parallel. Read-only. */
    fun power(ctx: CheckContext): BoardPower {
        val boards = showBoardsOf(ctx, props.wled.ignore)
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val states = try {
            boards.map { b -> b to CompletableFuture.supplyAsync({ runCatching { http.get("http://${b.host}/json/state").body }.getOrNull() }, pool) }
                .map { (b, f) -> b to f.get() }
        } finally {
            pool.shutdown()
        }
        var on = 0
        var unreachable = 0
        val offNames = mutableListOf<String>()
        for ((b, st) in states) {
            when {
                st == null -> unreachable++
                st.path("on").asBoolean(false) && st.path("bri").asInt(0) > 0 -> on++
                else -> offNames += b.name
            }
        }
        return BoardPower(on, offNames.size, unreachable, offNames)
    }
}
