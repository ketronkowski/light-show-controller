package com.ketronkowski.xlights.lipsync

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.preshow.CheckContext
import com.ketronkowski.xlights.preshow.CheckResult
import com.ketronkowski.xlights.preshow.PreShowChecker
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.brief
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One stream lip-sync measurement: FPP plays a click track that flashes the show, a recording
 * of the stream is analyzed at each click. [offsetMs] is video minus audio (clicksync.py's
 * `offset_ms`): positive = the picture is late. Accepts clicksync.py's `--json` output as is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LipSyncResult(
    @JsonAlias("offset_ms") val offsetMs: Double,
    /** Spread of the per-click offsets, ms (clicksync.py: p90 - p10). */
    val spreadMs: Double? = null,
    /** Clicks matched to a flash. */
    val pairs: Int,
    @JsonAlias("p10_ms") val p10Ms: Double? = null,
    @JsonAlias("p90_ms") val p90Ms: Double? = null,
    val measuredAt: Instant? = null,
    /** True when live-01's show feeder restarted since the previous measurement. */
    val feederRestarted: Boolean? = null,
    val note: String? = null,
    /** Filled in by the app when the result is posted. */
    val receivedAt: Instant? = null,
)

/** Keeps the latest posted lip-sync result under `<data-dir>/lipsync/last.json`. */
@Service
class LipSyncService(
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(LipSyncService::class.java)
    private val lastFile: Path = Path.of(dataDir, "lipsync", "last.json")

    fun last(): LipSyncResult? =
        if (Files.exists(lastFile)) runCatching { objectMapper.readValue(Files.readString(lastFile), LipSyncResult::class.java) }.getOrNull() else null

    fun save(result: LipSyncResult, now: Instant = Instant.now()): LipSyncResult {
        require(result.offsetMs.isFinite()) { "offsetMs must be a number" }
        require(result.pairs >= 0) { "pairs must be 0 or more" }
        val spread = result.spreadMs ?: if (result.p10Ms != null && result.p90Ms != null) result.p90Ms - result.p10Ms else null
        val stored = result.copy(spreadMs = spread, measuredAt = result.measuredAt ?: now, receivedAt = now)
        Files.createDirectories(lastFile.parent)
        val tmp = lastFile.resolveSibling("last.json.tmp")
        Files.writeString(tmp, objectMapper.writeValueAsString(stored))
        Files.move(tmp, lastFile, StandardCopyOption.REPLACE_EXISTING)
        log.info("Lip sync result: {} ms, {} pairs", stored.offsetMs, stored.pairs)
        return stored
    }
}

/**
 * "Stream" layer: reports the latest posted lip-sync measurement while it is fresh. Nothing is
 * played here; the measurement happens outside the app. Never a blocker: bad sync is a warning.
 * Until a first result is posted it reports nothing.
 */
@Component
class LipSyncChecker(
    private val service: LipSyncService,
    private val props: PreShowProperties,
) : PreShowChecker {
    override val layer = LAYER

    override fun check(ctx: CheckContext): List<CheckResult> {
        val last = runCatching { service.last() }.getOrElse { return listOf(CheckResult.warn(LAYER, NAME, TARGET, "couldn't read the last result: ${it.brief()}")) }
            ?: return emptyList()
        val at = last.measuredAt ?: last.receivedAt ?: return emptyList()
        val age = Duration.between(at, ctx.now)
        if (age.isNegative || age.toHours() >= props.lipSync.resultMaxAgeHours) {
            return listOf(CheckResult.skip(LAYER, NAME, TARGET, "no lip-sync measurement in the last ${props.lipSync.resultMaxAgeHours} h"))
        }
        val time = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.of(props.zone)).format(at)
        return listOf(result(last, props.lipSync, "measured $time"))
    }

    companion object {
        const val LAYER = "Stream"
        const val NAME = "Lip sync"
        private const val TARGET = "live stream"

        fun result(r: LipSyncResult, cfg: PreShowProperties.LipSync, note: String? = null): CheckResult {
            val ms = r.offsetMs.roundToLong()
            val dir = when { ms > 0 -> "picture $ms ms behind the sound"; ms < 0 -> "sound ${-ms} ms behind the picture"; else -> "in sync" }
            val details = buildList {
                add("${r.pairs} clicks")
                r.spreadMs?.let { add("spread ${it.roundToLong()} ms") }
                if (r.feederRestarted == true) add("stream feeder restarted since the last check")
                note?.let { add(it) }
            }.joinToString(", ")
            val msg = "$dir ($details)"
            return when {
                r.pairs < cfg.minPairs -> CheckResult.warn(LAYER, NAME, TARGET, "only ${r.pairs} clicks matched, can't trust it: $msg")
                abs(r.offsetMs) > cfg.warnMs -> CheckResult.warn(LAYER, NAME, TARGET, "out of sync, $msg (OK within ${cfg.warnMs.roundToLong()} ms)")
                else -> CheckResult.pass(LAYER, NAME, TARGET, msg)
            }
        }
    }
}
