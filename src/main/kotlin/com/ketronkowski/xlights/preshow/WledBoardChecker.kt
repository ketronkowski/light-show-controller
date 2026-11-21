package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.JsonNode
import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.validation.ValidationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Per-board WLED checks: answering, firmware, recent restarts, free memory, WiFi
 * signal, pre-show state, hardening, and LED counts against the xLights layout.
 * Read-only: only GETs.
 */
@Component
class WledBoardChecker(
    private val http: JsonHttp,
    private val validation: ValidationService,
    private val props: PreShowProperties,
) : PreShowChecker {
    private val log = LoggerFactory.getLogger(WledBoardChecker::class.java)
    override val layer = "Boards"
    private val cfg get() = props.wled

    internal data class Board(val name: String, val host: String)
    internal data class Seen(val board: Board, val info: JsonNode, val state: JsonNode?, val cfgJson: JsonNode?)

    override fun check(ctx: CheckContext): List<CheckResult> {
        val boards = showBoards(ctx)
        if (boards.isEmpty()) return listOf(CheckResult.fail(layer, "Boards", "xLights", "no WLED boards in the uploaded xLights layout"))

        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val fetched = try {
            boards.map { b -> b to CompletableFuture.supplyAsync({ fetch(b) }, pool) }
                .map { (b, f) -> b to f.get() }
        } finally {
            pool.shutdown()
        }

        val results = mutableListOf<CheckResult>()
        val seen = mutableListOf<Seen>()
        for ((board, outcome) in fetched) {
            outcome.fold(
                onSuccess = { seen += it },
                onFailure = { results += CheckResult.fail(layer, "Answering", board.name, "not answering at ${board.host} after ${cfg.attempts} ${if (cfg.attempts == 1) "try" else "tries"} (${it.brief()})") },
            )
        }
        results += restartChecks(seen, ctx.now)
        for (s in seen) results += boardChecks(s, ctx)
        results += layoutChecks(ctx, seen.map { it.board.name })
        return results
    }

    private fun showBoards(ctx: CheckContext): List<Board> {
        val fppIp = ctx.fppHost?.let { resolve(it) }
        return ctx.controllers
            .filter { !it.isFpp && !it.ipAddress.isNullOrBlank() }
            .filter { c -> cfg.ignore.none { it.equals(c.name, ignoreCase = true) } }
            .filter { c -> fppIp == null || resolve(c.ipAddress!!) != fppIp }
            .map { Board(it.name, it.ipAddress!!) }
    }

    private fun fetch(board: Board): Result<Seen> {
        var last: Throwable? = null
        repeat(cfg.attempts.coerceAtLeast(1)) { attempt ->
            try {
                val info = http.get("http://${board.host}/json/info").body
                val state = runCatching { http.get("http://${board.host}/json/state").body }.getOrNull()
                val cfgJson = runCatching { http.get("http://${board.host}/json/cfg").body }.getOrNull()
                return Result.success(Seen(board, info, state, cfgJson))
            } catch (e: Exception) {
                last = e
                log.info("Pre-show: {} at {} try {} failed: {}", board.name, board.host, attempt + 1, e.brief())
                if (attempt < cfg.attempts - 1) Thread.sleep(cfg.retryDelaySeconds * 1000)
            }
        }
        return Result.failure(last ?: IllegalStateException("no answer"))
    }

    private fun boardChecks(s: Seen, ctx: CheckContext): List<CheckResult> {
        val name = s.board.name
        val info = s.info
        val out = mutableListOf<CheckResult>()
        val ver = info.path("ver").asText("")
        val uptime = info.path("uptime").asLong(-1)
        out += CheckResult.pass(layer, "Answering", name, "v$ver, up ${if (uptime >= 0) humanDuration(uptime) else "?"}")

        if (cfg.expectedFirmware.isNotBlank() && ver != cfg.expectedFirmware) {
            out += CheckResult.warn(layer, "Firmware", name, "firmware $ver, expected ${cfg.expectedFirmware}")
        }

        val heap = info.path("freeheap").asInt(-1)
        if (heap in 0 until cfg.minFreeHeapBytes) {
            out += CheckResult.warn(layer, "Free memory", name, "free memory low: ${heap / 1024} KB (warn under ${cfg.minFreeHeapBytes / 1024} KB)")
        }

        // Only boards actually on WiFi report a real signal; Ethernet boards report 0.
        val rssi = info.path("wifi").path("rssi").asInt(0)
        if (rssi in -120..-1 && rssi < cfg.minWifiRssi) {
            out += CheckResult.warn(layer, "WiFi signal", name, "weak WiFi signal: $rssi dBm (warn under ${cfg.minWifiRssi} dBm)")
        }

        val live = info.path("live").asBoolean(false)
        if (live) {
            val source = info.path("lip").asText("")
            val fppIp = ctx.fppHost?.let { resolve(it) }
            if (fppIp != null && source.isNotBlank() && source != fppIp) {
                out += CheckResult.warn(layer, "Show data", name, "receiving live data from $source, not FPP ($fppIp)")
            } else if (ctx.beforeShow) {
                out += CheckResult.warn(layer, "Show data", name, "already receiving live data before the show${if (source.isNotBlank()) " from $source" else ""}")
            }
        } else if (s.state != null && cfg.expectedPreset > 0 && ctx.beforeShow) {
            // Being off the boot preset is normal (FPP, the app or an effect change all clear
            // it); only a board that is visibly lit before the show is worth a warning.
            val lit = litSegments(s.state)
            if (lit > 0) {
                val ps = s.state.path("ps").asInt(-1)
                val where = if (ps > 0) "preset $ps" else "no preset"
                out += CheckResult.warn(layer, "Pre-show state", name,
                    "lit before the show: $lit segment(s) showing color or an effect ($where, boot preset is ${cfg.expectedPreset})")
            }
            if (s.state.path("nl").path("on").asBoolean(false)) {
                out += CheckResult.warn(layer, "Pre-show state", name, "nightlight timer is on")
            }
        }

        s.cfgJson?.let { c ->
            val ota = c.path("ota")
            val problems = buildList {
                if (ota.has("lock") && !ota.path("lock").asBoolean()) add("OTA lock off")
                if (ota.path("aota").asBoolean(false)) add("ArduinoOTA on")
                val behav = c.path("ap").path("behav")
                if (behav.isInt && behav.asInt() != 3 && !isWifiBoard(rssi)) add("fallback access point not button-only (behav ${behav.asInt()})")
            }
            if (problems.isNotEmpty()) out += CheckResult.warn(layer, "Hardening", name, problems.joinToString(", "))
        }
        return out
    }

    private fun isWifiBoard(rssi: Int) = rssi in -120..-1

    /**
     * Restarts in the last 24 hours. When most boards came up within a few minutes of
     * each other that is the show circuit powering up, reported once as a pass; a
     * board that restarted on its own is a warning.
     */
    internal fun restartChecks(seen: List<Seen>, now: Instant): List<CheckResult> {
        val bootTimes = seen.mapNotNull { s ->
            val up = s.info.path("uptime").asLong(-1)
            if (up < 0) null else s.board.name to now.minusSeconds(up)
        }
        val recent = bootTimes.filter { (_, t) -> Duration.between(t, now) < Duration.ofHours(24) }
        if (recent.isEmpty()) return emptyList()

        // Largest group of boots within 10 minutes of each other.
        val cluster = recent.map { (_, anchor) ->
            recent.filter { (_, t) -> Duration.between(anchor, t).abs() <= Duration.ofMinutes(10) }
        }.maxBy { it.size }
        val powerUp = cluster.size >= 3 && cluster.size * 2 >= seen.size
        val clock = DateTimeFormatter.ofPattern("h:mm a").withZone(java.time.ZoneId.of(props.zone))

        val out = mutableListOf<CheckResult>()
        val alone = if (powerUp) {
            val at = cluster.minOf { it.second }
            out += CheckResult.pass(layer, "Restarts", "${cluster.size} boards", "powered up together around ${clock.format(at)} (circuit power-up)")
            recent - cluster.toSet()
        } else recent
        for ((name, t) in alone) {
            out += CheckResult.warn(layer, "Restarts", name, "restarted ${humanDuration(Duration.between(t, now).seconds)} ago, around ${clock.format(t)}")
        }
        return out
    }

    private fun layoutChecks(ctx: CheckContext, reachable: List<String>): List<CheckResult> {
        if (reachable.isEmpty()) return emptyList()
        val report = runCatching { validation.validate(ctx.showDir, 0, reachable.toSet()) }
            .getOrElse { return listOf(CheckResult.warn(layer, "LED count", "all boards", "could not compare with xLights: ${it.brief()}")) }
        return report.controllerValidations.mapNotNull { cv ->
            val device = cv.wledDevice ?: return@mapNotNull null
            val name = cv.xLightsController.name
            when {
                !cv.totalLedsMatch -> CheckResult.fail(layer, "LED count", name,
                    "WLED has ${device.totalLeds} LEDs, xLights expects ${cv.expectedTotalLeds}: later pixels will be shifted")
                else -> CheckResult.pass(layer, "LED count", name, "${device.totalLeds} LEDs match xLights")
            }
        }
    }

    private fun resolve(host: String): String =
        runCatching { InetAddress.getByName(host).hostAddress }.getOrDefault(host)

    /** Segments that are visibly lit: board on with brightness, segment on, and a color or a moving effect. */
    internal fun litSegments(state: JsonNode): Int {
        if (!state.path("on").asBoolean(true) || state.path("bri").asInt(0) == 0) return 0
        return state.path("seg").count { seg ->
            seg.path("on").asBoolean(true) && seg.path("bri").asInt(255) > 0 &&
                (seg.path("fx").asInt(0) != 0 || seg.path("col").path(0).any { it.asInt(0) > 0 })
        }
    }
}
