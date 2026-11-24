package com.ketronkowski.xlights.lighttest

import com.fasterxml.jackson.databind.JsonNode
import com.ketronkowski.xlights.preshow.JsonHttp
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.Base64

/** Lights channel ranges through FPP's test mode, so the real show data path is what gets tested. */
interface Lighter {
    /** True when FPP is not playing anything, so test mode won't fight a show. */
    fun idle(host: String): Boolean
    /** FPP's first channel for each controller address, from its network outputs. */
    fun controllerStarts(host: String): Map<String, Int>
    /** Lights channels [first]..[last] (1-based, inclusive) in [color]; everything else goes dark. */
    fun light(host: String, first: Int, last: Int, color: String)
    /** Ends test mode; the boards go back to their own (black) look. */
    fun stop(host: String)
}

@Component
class FppTester(
    private val http: JsonHttp,
    @Value("\${xlights.fpp.username:admin}") private val username: String,
    @Value("\${xlights.fpp.password:}") private val password: String,
) : Lighter {
    private val auth: Map<String, String>
        get() = if (password.isEmpty()) emptyMap()
        else mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray()))

    override fun idle(host: String): Boolean {
        val s = http.get("http://$host/api/fppd/status", auth).body
        return s.path("status_name").asText("") == "idle" || s.path("status").asInt(-1) == 0
    }

    override fun controllerStarts(host: String): Map<String, Int> =
        starts(http.get("http://$host/api/channel/output/universeOutputs", auth).body)

    override fun light(host: String, first: Int, last: Int, color: String) {
        command(host, "Test Start", listOf("1000", "RGB Single Color", "$first-$last", color))
    }

    override fun stop(host: String) {
        command(host, "Test Stop", emptyList())
    }

    private fun command(host: String, name: String, args: List<String>) {
        http.post("http://$host/api/command", mapOf("command" to name, "args" to args), auth)
    }

    companion object {
        /** Lowest start channel of the enabled, active outputs to each address (resolved to an IP). */
        fun starts(outputs: JsonNode): Map<String, Int> {
            val out = mutableMapOf<String, Int>()
            for (block in outputs.path("channelOutputs")) {
                if (block.path("enabled").asInt(1) != 1) continue
                for (u in block.path("universes")) {
                    if (u.path("active").asInt(1) != 1) continue
                    val ip = resolve(u.path("address").asText(""))
                    val start = u.path("startChannel").asInt(0)
                    if (ip.isBlank() || start <= 0) continue
                    out[ip] = minOf(out[ip] ?: Int.MAX_VALUE, start)
                }
            }
            return out
        }

        fun resolve(host: String): String =
            if (host.isBlank()) host else runCatching { InetAddress.getByName(host).hostAddress }.getOrDefault(host)
    }
}
