package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.preshow.HomeAssistantClient
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.brief
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Builds the Show Control page's status: FPP, the show circuit and plugs (through
 * Home Assistant) and every WLED board, fetched in parallel. Read-only.
 */
@Service
class ShowStatusService(
    private val fpp: FppShowClient,
    private val ha: HomeAssistantClient,
    private val props: PreShowProperties,
    private val parser: XLightsConfigParser,
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(ShowStatusService::class.java)
    private val xlightsDir: Path = Path.of(dataDir, "xlights")
    private val pool = Executors.newVirtualThreadPerTaskExecutor()
    private val wled = FppShowClient.quickClient(Duration.ofMillis(1500), Duration.ofSeconds(3))
    private val history = ConcurrentLinkedDeque<PowerSample>()
    private val streamHistory = ConcurrentLinkedDeque<StreamSample>()

    @Volatile private var layout: Pair<Instant, List<XLightsController>>? = null
    @Volatile private var cached: ShowStatus? = null

    private companion object {
        const val NOMINAL_VOLTS = 120.0
        val HISTORY_SPAN: Duration = Duration.ofHours(1)
        val CACHE_FOR: Duration = Duration.ofSeconds(2)
    }

    fun fppHost(): String? = props.fpp.host.ifBlank { null } ?: controllers().firstOrNull { it.isFpp }?.ipAddress

    /** The status, reused for 2 s so several open pages don't multiply the polling. */
    fun status(): ShowStatus {
        cached?.takeIf { Duration.between(it.at, Instant.now()) < CACHE_FOR }?.let { return it }
        val now = Instant.now()
        val host = fppHost()
        val fppF = CompletableFuture.supplyAsync({ fppNow(host) }, pool)
        val powerF = CompletableFuture.supplyAsync({ powerNow() }, pool)
        val boardsF = CompletableFuture.supplyAsync({ boardsNow() }, pool)
        val streamF = CompletableFuture.supplyAsync({ streamNow() }, pool)
        val status = ShowStatus(
            at = now,
            fpp = runCatching { fppF.get(6, TimeUnit.SECONDS) }.getOrElse { FppNow(host, false, error = "timed out") },
            power = runCatching { powerF.get(6, TimeUnit.SECONDS) }.getOrElse { PowerNow(ha.configured) },
            boards = runCatching { boardsF.get(6, TimeUnit.SECONDS) }.getOrElse { emptyList() },
            stream = runCatching { streamF.get(6, TimeUnit.SECONDS) }.getOrElse { StreamNow(streamConfigured()) },
        )
        cached = status
        return status
    }

    fun playlists(): List<PlaylistSummary> {
        val host = fppHost() ?: return emptyList()
        val names = fpp.playlistNames(host)
        val pinned = props.fpp.validatePlaylists + props.fpp.showPlaylists
        val ordered = names.sortedWith(compareBy<String> { n ->
            pinned.indexOfFirst { it.equals(n, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it }
        }.thenBy { it.lowercase() })
        return ordered.map { n -> CompletableFuture.supplyAsync({ fpp.playlist(host, n) }, pool) }.map { it.join() }
    }

    // ---- FPP -----------------------------------------------------------------------

    private fun fppNow(host: String?): FppNow {
        host ?: return FppNow(null, false, error = "no FPP address in settings or the xLights layout")
        val status = try {
            fpp.get(host, "/system/status")
        } catch (e: Exception) {
            return FppNow(host, false, error = e.brief())
        }
        val info = runCatching { fpp.get(host, "/system/info") }.getOrNull()
        return parseFpp(host, status, info, props.fpp.expectedVolume)
    }

    // ---- Power ---------------------------------------------------------------------

    private fun powerNow(): PowerNow {
        if (!ha.configured) return PowerNow(configured = false)
        val cfg = props.ha
        val circuit = circuitNow()
        val plugs = cfg.plugs.map { (label, entity) ->
            CompletableFuture.supplyAsync({
                val state = runCatching { ha.state(entity).path("state").asText("unknown") }.getOrDefault("unreadable")
                val watts = cfg.plugPower[label]?.let { readNumber(it) }
                PlugNow(label, entity, state, watts, cfg.plugBoards[label] ?: listOf(label))
            }, pool)
        }.map { it.join() }
        val switches = cfg.switches.map { (label, entity) ->
            CompletableFuture.supplyAsync({ SwitchNow(label, entity, switchState(entity)) }, pool)
        }.map { it.join() }
        return PowerNow(true, circuit, plugs, history.toList(), switches)
    }

    private fun switchState(entity: String): String =
        runCatching { ha.state(entity).path("state").asText("unknown") }.getOrDefault("unreadable")

    /**
     * Turns one of the configured [PreShowProperties.HomeAssistant.switches] on or off, by its
     * label (never an arbitrary entity), and returns its state afterwards.
     */
    fun setSwitch(label: String, on: Boolean): SwitchNow {
        val (name, entity) = props.ha.switches.entries.firstOrNull { it.key.equals(label, ignoreCase = true) }?.toPair()
            ?: throw NoSuchElementException("no switch named \"$label\"")
        check(ha.configured) { "Home Assistant is not configured" }
        log.info("Show Control: turning {} ({}) {}", name, entity, if (on) "on" else "off")
        ha.setSwitch(entity, on)
        cached = null
        return SwitchNow(name, entity, switchState(entity))
    }

    private fun circuitNow(): CircuitNow? {
        val cfg = props.ha
        if (cfg.circuitSwitchEntity.isBlank() && cfg.powerEntity.isBlank() && cfg.currentEntity.isBlank()) return null
        val state = cfg.circuitSwitchEntity.ifBlank { null }?.let { e ->
            runCatching { ha.state(e).path("state").asText("unknown") }.getOrDefault("unreadable")
        }
        val watts = cfg.powerEntity.ifBlank { null }?.let { readNumber(it) }
        val measuredAmps = cfg.currentEntity.ifBlank { null }?.let { readNumber(it) }
        val volts = cfg.voltageEntity.ifBlank { null }?.let { readNumber(it) }
        return CircuitNow(
            entity = cfg.circuitSwitchEntity,
            state = state,
            watts = watts,
            amps = measuredAmps ?: watts?.let { it / (volts?.takeIf { v -> v > 50 } ?: NOMINAL_VOLTS) },
            ampsEstimated = measuredAmps == null,
            volts = volts,
        )
    }

    private fun readNumber(entity: String): Double? =
        runCatching { ha.state(entity).path("state").asText("").toDoubleOrNull() }.getOrNull()

    /** One show-circuit power/current/voltage sample every 30 s, kept for an hour, for the page's sparkline. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    fun sample() {
        if (!ha.configured || props.ha.powerEntity.isBlank()) return
        val w = readNumber(props.ha.powerEntity) ?: return
        val volts = props.ha.voltageEntity.ifBlank { null }?.let { readNumber(it) }
        val amps = props.ha.currentEntity.ifBlank { null }?.let { readNumber(it) }
            ?: w / (volts?.takeIf { it > 50 } ?: NOMINAL_VOLTS)
        val now = Instant.now()
        history.addLast(PowerSample(now, w, amps, volts))
        while (history.peekFirst()?.let { Duration.between(it.at, now) > HISTORY_SPAN } == true) history.pollFirst()
    }

    // ---- Live stream -----------------------------------------------------------------

    private fun streamConfigured(): Boolean = ha.configured && with(props.ha) {
        listOf(streamViewersEntity, streamUploadEntity, feedSourceEntity, streamAudioEntity).any { it.isNotBlank() }
    }

    /** Reads live-01's stream sensors from Home Assistant; never calls live-01 itself (it is the encoder). */
    internal fun streamNow(): StreamNow {
        if (!streamConfigured()) return StreamNow(configured = false)
        val cfg = props.ha
        fun read(entity: String): JsonNode? = entity.ifBlank { null }?.let { runCatching { ha.state(it) }.getOrNull() }
        fun text(n: JsonNode?) = n?.path("state")?.asText("")?.ifBlank { null }
        val viewersF = CompletableFuture.supplyAsync({ read(cfg.streamViewersEntity) }, pool)
        val uploadF = CompletableFuture.supplyAsync({ read(cfg.streamUploadEntity) }, pool)
        val sourceF = CompletableFuture.supplyAsync({ read(cfg.feedSourceEntity) }, pool)
        val audioF = CompletableFuture.supplyAsync({ read(cfg.streamAudioEntity) }, pool)
        val viewers = viewersF.join()
        val upload = uploadF.join()
        val updated = listOfNotNull(viewers, upload)
            .mapNotNull { n -> runCatching { Instant.parse(n.path("last_reported").asText(n.path("last_updated").asText())) }.getOrNull() }
            .maxOrNull()
        return StreamNow(
            configured = true,
            viewers = text(viewers)?.toDoubleOrNull()?.toInt(),
            uploadMbps = text(upload)?.toDoubleOrNull(),
            feedSource = text(sourceF.join()),
            audio = text(audioF.join()),
            updated = updated,
            history = streamHistory.toList(),
        )
    }

    /** One viewer/upload sample a minute (HA refreshes live-01's status about that often), kept for an hour. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    fun sampleStream() {
        if (!ha.configured || (props.ha.streamViewersEntity.isBlank() && props.ha.streamUploadEntity.isBlank())) return
        val s = streamNow()
        if (s.viewers == null && s.uploadMbps == null) return
        val now = Instant.now()
        streamHistory.addLast(StreamSample(now, s.viewers, s.uploadMbps))
        while (streamHistory.peekFirst()?.let { Duration.between(it.at, now) > HISTORY_SPAN } == true) streamHistory.pollFirst()
    }

    // ---- WLED boards -----------------------------------------------------------------

    private fun boardsNow(): List<BoardNow> {
        val plugOf = props.ha.plugs.keys.flatMap { label -> (props.ha.plugBoards[label] ?: listOf(label)).map { it.lowercase() to label } }.toMap()
        val ignore = props.wled.ignore.map { it.lowercase() }.toSet()
        return controllers()
            .filter { !it.isFpp && !it.ipAddress.isNullOrBlank() && it.name.lowercase() !in ignore }
            .map { c -> CompletableFuture.supplyAsync({ boardNow(c.name, c.ipAddress!!, plugOf[c.name.lowercase()]) }, pool) }
            .map { it.join() }
            .sortedBy { it.name.lowercase() }
    }

    private fun boardNow(name: String, ip: String, plug: String?): BoardNow = try {
        val text = wled.get().uri("http://$ip/json/si").retrieve().body(String::class.java) ?: ""
        parseBoard(name, ip, plug, objectMapper.readTree(text))
    } catch (e: Exception) {
        BoardNow(name, ip, reachable = false, plug = plug, error = e.brief())
    }

    private fun controllers(): List<XLightsController> {
        layout?.takeIf { Duration.between(it.first, Instant.now()) < Duration.ofMinutes(1) }?.let { return it.second }
        val list = try {
            parser.parse(xlightsDir)
        } catch (e: Exception) {
            log.debug("No xLights layout for Show Control: {}", e.message)
            emptyList()
        }
        layout = Instant.now() to list
        return list
    }
}

/** FPP's /api/system/status (plus /api/system/info) as the page shows it. */
internal fun parseFpp(host: String, s: JsonNode, info: JsonNode?, expectedVolume: Int?): FppNow {
    fun text(n: JsonNode) = n.asText("").ifBlank { null }
    val pl = s.path("current_playlist")
    val sched = s.path("scheduler")
    val media = info?.path("Utilization")?.path("Disk")?.path("Media")
    val free = media?.path("Free")?.asDouble(-1.0) ?: -1.0
    val total = media?.path("Total")?.asDouble(-1.0) ?: -1.0
    val temp = s.path("sensors").firstOrNull { sn ->
        sn.path("valueType").asText("").equals("Temperature", ignoreCase = true) ||
            sn.path("label").asText("").contains("CPU", ignoreCase = true)
    }?.path("value")?.asDouble(Double.NaN)?.takeIf { !it.isNaN() }
    return FppNow(
        host = host,
        reachable = true,
        version = info?.path("Version")?.let(::text),
        status = text(s.path("status_name")),
        playlist = text(pl.path("playlist")),
        index = pl.path("index").asText("").toIntOrNull()?.takeIf { it > 0 },
        count = pl.path("count").asText("").toIntOrNull()?.takeIf { it > 0 },
        sequence = FppShowClient.stripExt(text(s.path("current_sequence"))),
        song = FppShowClient.stripExt(text(s.path("current_song"))),
        secondsPlayed = s.path("seconds_played").asText("").toDoubleOrNull()?.toInt(),
        secondsRemaining = s.path("seconds_remaining").asText("").toDoubleOrNull()?.toInt(),
        volume = s.path("volume").asText("").toIntOrNull(),
        schedulerEnabled = if (sched.has("enabled")) sched.path("enabled").asInt(1) != 0 else null,
        scheduledPlaylist = text(sched.path("currentPlaylist").path("playlistName")),
        scheduledEnd = text(sched.path("currentPlaylist").path("scheduledEndTimeStr")),
        nextPlaylist = text(sched.path("nextPlaylist").path("playlistName")) ?: text(s.path("next_playlist").path("playlist")),
        nextStart = text(sched.path("nextPlaylist").path("scheduledStartTimeStr")) ?: text(s.path("next_playlist").path("start_time")),
        cpuTempC = temp,
        mediaFreeGb = if (free >= 0) free / 1e9 else null,
        mediaFreePercent = if (free >= 0 && total > 0) free * 100 / total else null,
        powerBad = s.path("powerBad").asBoolean(false),
        expectedVolume = expectedVolume,
    )
}

/** WLED's /json/si (state + info in one reply). */
internal fun parseBoard(name: String, ip: String, plug: String?, si: JsonNode): BoardNow {
    val info = si.path("info")
    val state = si.path("state")
    val leds = info.path("leds")
    return BoardNow(
        name = name,
        ip = ip,
        reachable = true,
        live = info.path("live").asBoolean(false),
        milliamps = leds.path("pwr").takeIf { it.isNumber }?.asInt(),
        maxMilliamps = leds.path("maxpwr").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 },
        on = state.path("on").takeIf { it.isBoolean }?.asBoolean(),
        brightness = state.path("bri").takeIf { it.isNumber }?.asInt(),
        plug = plug,
    )
}
