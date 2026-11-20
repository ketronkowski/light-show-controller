package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Falcon Player checks: fppd running, scheduler on with a show playlist at show
 * start tonight, every playlist file present, outputs to every board, clock,
 * temperature, disk, volume, MQTT, under-voltage and recent restarts. Read-only.
 */
@Component
class FppChecker(
    private val http: JsonHttp,
    private val props: PreShowProperties,
    @Value("\${xlights.fpp.username:admin}") private val username: String,
    @Value("\${xlights.fpp.password:}") private val password: String,
) : PreShowChecker {
    override val layer = "FPP"
    private val cfg get() = props.fpp

    private val auth: Map<String, String>
        get() = if (password.isEmpty()) emptyMap()
        else mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray()))

    override fun check(ctx: CheckContext): List<CheckResult> {
        val host = ctx.fppHost ?: return listOf(CheckResult.fail(layer, "Answering", "FPP", "no FPP address in settings or the xLights layout"))
        val target = "FPP"
        val base = "http://$host/api"
        val out = mutableListOf<CheckResult>()

        val info = try {
            http.get("$base/system/info", auth).body
        } catch (e: Exception) {
            return listOf(CheckResult.fail(layer, "Answering", target, "not answering at $host (${e.brief()})"))
        }
        out += CheckResult.pass(layer, "Answering", target, "FPP ${info.path("Version").asText("?")}, mode ${info.path("Mode").asText("?")}")
        out += diskCheck(info, target)

        val statusReply = runCatching { http.get("$base/system/status", auth) }.getOrElse {
            out += CheckResult.fail(layer, "fppd", target, "status not readable (${it.brief()})")
            null
        }
        statusReply?.let { out += statusChecks(it.body, target, ctx) ; out += clockCheck(it.serverTime, ctx.now, target) }

        out += scheduleCheck(base, ctx, target)
        out += playlistChecks(base, target)
        out += outputChecks(base, ctx, target)
        return out
    }

    private fun diskCheck(info: JsonNode, target: String): List<CheckResult> {
        val media = info.path("Utilization").path("Disk").path("Media")
        val free = media.path("Free").asDouble(-1.0)
        val total = media.path("Total").asDouble(-1.0)
        if (free < 0 || total <= 0) return emptyList()
        val pct = free * 100 / total
        val gb = "%.0f GB free of %.0f GB".format(free / 1e9, total / 1e9)
        return listOf(
            if (pct < cfg.minMediaFreePercent) CheckResult.warn(layer, "Disk", target, "media disk low: $gb")
            else CheckResult.pass(layer, "Disk", target, gb)
        )
    }

    internal fun statusChecks(s: JsonNode, target: String, ctx: CheckContext): List<CheckResult> {
        val out = mutableListOf<CheckResult>()
        val fppd = s.path("fppd").asText("")
        val statusName = s.path("status_name").asText("unknown")
        out += if (fppd.equals("Not Running", ignoreCase = true) || statusName in setOf("stopped", "unknown", "updating")) {
            CheckResult.fail(layer, "fppd", target, "fppd is not running (status: $statusName)")
        } else {
            CheckResult.pass(layer, "fppd", target, "running, $statusName")
        }

        val scheduler = s.path("scheduler")
        if (scheduler.has("enabled") && scheduler.path("enabled").asInt(1) == 0) {
            out += CheckResult.fail(layer, "Scheduler", target, "the FPP scheduler is disabled")
        }

        val warnings = s.path("warnings").map { it.asText() }.filter { it.isNotBlank() }
        if (warnings.isNotEmpty()) {
            out += CheckResult.warn(layer, "FPP warnings", target, warnings.take(3).joinToString("; ") + if (warnings.size > 3) " (+${warnings.size - 3} more)" else "")
        }

        val mqtt = s.path("MQTT")
        if (mqtt.path("configured").asBoolean(false) && !mqtt.path("connected").asBoolean(true)) {
            out += CheckResult.warn(layer, "MQTT", target, "MQTT to Home Assistant is not connected")
        }

        cfg.expectedVolume?.let { want ->
            val vol = s.path("volume").asInt(-1)
            if (vol >= 0 && vol != want) out += CheckResult.warn(layer, "Volume", target, "volume is $vol, expected $want (radio and stream levels)")
        }

        if (s.path("powerBad").asBoolean(false)) {
            out += CheckResult.warn(layer, "Power supply", target, "the Pi reports under-voltage: check its power supply")
        }

        s.path("sensors").firstOrNull { sn ->
            sn.path("valueType").asText("").equals("Temperature", ignoreCase = true) ||
                sn.path("label").asText("").contains("CPU", ignoreCase = true)
        }?.let { sn ->
            val t = sn.path("value").asDouble(Double.NaN)
            if (!t.isNaN()) {
                out += if (t > cfg.maxCpuTempC) CheckResult.warn(layer, "CPU temperature", target, "CPU at %.0f °C (warn over %.0f °C)".format(t, cfg.maxCpuTempC))
                else CheckResult.pass(layer, "CPU temperature", target, "%.0f °C".format(t))
            }
        }

        val sysUp = s.path("systemUptimeTotalSeconds").asLong(-1)
        if (sysUp in 0 until 86_400) {
            out += CheckResult.warn(layer, "Restarts", target, "FPP restarted ${humanDuration(sysUp)} ago")
        }
        return out
    }

    internal fun clockCheck(serverTime: Instant?, now: Instant, target: String): List<CheckResult> {
        serverTime ?: return emptyList()
        val drift = Duration.between(now, serverTime).abs().seconds
        return listOf(
            if (drift > cfg.maxClockDriftSeconds) CheckResult.warn(layer, "Clock", target, "FPP clock is off by about $drift s: the schedule and Remote Falcon depend on it")
            else CheckResult.pass(layer, "Clock", target, "within $drift s")
        )
    }

    private fun scheduleCheck(base: String, ctx: CheckContext, target: String): List<CheckResult> {
        val today = ctx.localNow.toLocalDate()
        val start = today.atStartOfDay(ctx.zone).toEpochSecond()
        val end = today.plusDays(1).atStartOfDay(ctx.zone).toEpochSecond()
        val items = try {
            http.get("$base/fppd/schedule/range?start=$start&end=$end", auth).body.path("schedule").path("items")
        } catch (e: Exception) {
            return listOf(CheckResult.warn(layer, "Tonight's schedule", target, "schedule not readable (${e.brief()})"))
        }
        return listOf(scheduleResult(items, ctx, target))
    }

    internal fun scheduleResult(items: JsonNode, ctx: CheckContext, target: String): CheckResult {
        val showAt = ctx.localNow.toLocalDate().atTime(ctx.showStart).atZone(ctx.zone).toEpochSecond()
        val clock = DateTimeFormatter.ofPattern("h:mm a").withZone(ctx.zone)
        val hit = items.firstOrNull { item ->
            val playlist = item.path("playlist").asText("")
            item.path("enabled").asBoolean(true) &&
                !item.path("overridden").asBoolean(false) &&
                cfg.showPlaylists.any { it.equals(playlist, ignoreCase = true) } &&
                item.path("startTime").asLong(Long.MAX_VALUE) <= showAt &&
                item.path("endTime").asLong(0) > showAt
        }
        val wanted = cfg.showPlaylists.joinToString(" or ")
        return if (hit != null) {
            val from = clock.format(Instant.ofEpochSecond(hit.path("startTime").asLong()))
            val to = clock.format(Instant.ofEpochSecond(hit.path("endTime").asLong()))
            CheckResult.pass(layer, "Tonight's schedule", target, "${hit.path("playlist").asText()} scheduled $from to $to")
        } else {
            CheckResult.fail(layer, "Tonight's schedule", target, "no $wanted playlist scheduled at ${clock.format(Instant.ofEpochSecond(showAt))} tonight")
        }
    }

    private fun playlistChecks(base: String, target: String): List<CheckResult> {
        if (cfg.validatePlaylists.isEmpty()) return emptyList()
        val all = try {
            http.get("$base/playlists/validate", auth).body
        } catch (e: Exception) {
            return listOf(CheckResult.warn(layer, "Playlist files", target, "playlists not readable (${e.brief()})"))
        }
        return playlistResults(all)
    }

    internal fun playlistResults(all: JsonNode): List<CheckResult> = cfg.validatePlaylists.map { name ->
        val pl = all.firstOrNull { it.path("name").asText().equals(name, ignoreCase = true) }
        val items = pl?.path("total_items")?.asText("")?.toIntOrNull()
        when {
            pl == null -> CheckResult.fail(layer, "Playlist files", name, "playlist $name not found on FPP")
            !pl.path("valid").asBoolean(true) -> {
                val msgs = pl.path("messages").map { it.asText() }
                CheckResult.fail(layer, "Playlist files", name, msgs.take(3).joinToString("; ").ifBlank { "playlist has problems" } +
                    if (msgs.size > 3) " (+${msgs.size - 3} more)" else "")
            }
            items == 0 -> CheckResult.fail(layer, "Playlist files", name, "playlist $name is empty")
            else -> CheckResult.pass(layer, "Playlist files", name, "all ${items ?: "?"} items present")
        }
    }

    private fun outputChecks(base: String, ctx: CheckContext, target: String): List<CheckResult> {
        val outputs = try {
            http.get("$base/channel/output/universeOutputs", auth).body
        } catch (e: Exception) {
            return listOf(CheckResult.warn(layer, "Outputs", target, "outputs not readable (${e.brief()})"))
        }
        return outputResults(outputs, ctx)
    }

    internal fun outputResults(outputs: JsonNode, ctx: CheckContext): List<CheckResult> {
        val blocks = outputs.path("channelOutputs")
        if (blocks.none { it.path("enabled").asInt(1) == 1 }) {
            return listOf(CheckResult.fail(layer, "Outputs", "FPP", "FPP's network outputs are disabled: no board will get show data"))
        }
        val universes = blocks.filter { it.path("enabled").asInt(1) == 1 }.flatMap { b -> b.path("universes").toList() }
        val fppIp = ctx.fppHost?.let { resolve(it) }
        val out = mutableListOf<CheckResult>()
        for (c in ctx.controllers.filter { !it.isFpp && !it.ipAddress.isNullOrBlank() }) {
            val ip = resolve(c.ipAddress!!)
            if (ip == fppIp) continue
            if (props.wled.ignore.any { it.equals(c.name, ignoreCase = true) }) continue
            val mine = universes.filter { resolve(it.path("address").asText("")) == ip }
            val active = mine.filter { it.path("active").asInt(1) == 1 }
            val channels = active.sumOf { it.path("channelCount").asInt(0) }
            out += when {
                mine.isEmpty() -> CheckResult.fail(layer, "Outputs", c.name, "FPP has no output to ${c.ipAddress}: run FPP Connect from xLights")
                active.isEmpty() -> CheckResult.fail(layer, "Outputs", c.name, "FPP output to ${c.ipAddress} is turned off")
                c.totalChannels > 0 && channels != c.totalChannels -> CheckResult.warn(layer, "Outputs", c.name,
                    "FPP sends $channels channels, xLights has ${c.totalChannels}: re-run FPP Connect")
                else -> CheckResult.pass(layer, "Outputs", c.name, "$channels channels")
            }
        }
        return out
    }

    private fun resolve(host: String): String =
        if (host.isBlank()) host else runCatching { InetAddress.getByName(host).hostAddress }.getOrDefault(host)
}
