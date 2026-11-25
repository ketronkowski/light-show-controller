package com.ketronkowski.xlights.lighttest

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.brief
import com.ketronkowski.xlights.validation.ValidationService
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore
import kotlin.io.path.exists
import kotlin.io.path.readText

class LightTestBusyException : RuntimeException("the light test or calibration is already running")
class LightTestAborted : RuntimeException("stopped from the page")

/**
 * The camera light test. FPP's test mode lights one xLights model at a time (so the
 * whole show path is exercised: FPP, network, board, port, string), the Protect camera
 * takes a picture, and each picture is compared with an all-dark one.
 *
 * Calibration (one dark night) learns where each model appears in the picture. The
 * nightly test lights every visible model again and reports ON / DIM / OFF.
 * Test mode is always stopped at the end, even on errors; a restart mid-test stops it too.
 */
@Service
class LightTestService(
    private val lighter: Lighter,
    private val camera: Camera,
    private val parser: XLightsConfigParser,
    private val validation: ValidationService,
    private val wled: WledApiClient,
    private val props: PreShowProperties,
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(LightTestService::class.java)
    private val root: Path = Path.of(dataDir, "lighttest")
    private val calFile = root.resolve("calibration.json")
    private val calImage = root.resolve("calibration.jpg")
    private val lastRunFile = root.resolve("last-run.json")
    private val lastRunImage = root.resolve("last-run.jpg")
    /** Present while FPP is in test mode, so a restart can switch it off. */
    private val activeMarker = root.resolve("active-fpp-host")
    /** A picture of each model lit on its own, from the latest calibration and test. */
    val photos = ModelPhotos(root)
    private val xlightsDir: Path = Path.of(dataDir, "xlights")
    private val cfg get() = props.lights
    private val zone get() = ZoneId.of(props.zone)

    // A semaphore, not a lock: calibration takes it on the request thread and gives it back
    // from its background thread, which a ReentrantLock refuses (it stayed "busy" forever).
    private val lock = Semaphore(1)
    private val abort = AtomicBoolean(false)
    @Volatile private var progress = LightTestStatus(busy = false)

    // ── Status and stored results ──────────────────────────────────────────

    fun status(): LightTestStatus {
        val cal = calibration()
        return progress.copy(
            busy = lock.availablePermits() == 0,
            calibratedAt = cal?.createdAt,
            calibratedModels = cal?.models?.size ?: 0,
            visibleModels = cal?.visible?.size ?: 0,
            configured = cfg.configured,
        )
    }

    fun calibration(): Calibration? =
        if (calFile.exists()) runCatching { objectMapper.readValue(calFile.readText(), Calibration::class.java) }.getOrNull() else null

    fun calibrationImage(): ByteArray? = if (calImage.exists()) Files.readAllBytes(calImage) else null

    fun lastRun(): LightRun? =
        if (lastRunFile.exists()) runCatching { objectMapper.readValue(lastRunFile.readText(), LightRun::class.java) }.getOrNull() else null

    fun lastRunImage(): ByteArray? = if (lastRunImage.exists()) Files.readAllBytes(lastRunImage) else null

    fun requestAbort() {
        abort.set(true)
    }

    /** Switches FPP's test mode off if a restart interrupted a test. */
    @EventListener(ApplicationReadyEvent::class)
    fun recoverAfterRestart() {
        if (!activeMarker.exists()) return
        val host = runCatching { activeMarker.readText().trim() }.getOrDefault("")
        log.warn("Light test was interrupted by a restart; stopping FPP test mode on {}", host)
        if (host.isNotBlank()) runCatching { lighter.stop(host) }.onFailure { log.warn("Could not stop FPP test mode: {}", it.brief()) }
        runCatching { Files.deleteIfExists(activeMarker) }
    }

    // ── Guards ─────────────────────────────────────────────────────────────

    /**
     * Why lighting isn't allowed right now, or null when it is. Show hours only stop
     * scheduled runs: a person who starts one by hand has stopped the show on purpose.
     * FPP must be idle either way.
     */
    fun blockedReason(fppHost: String?, now: Instant = Instant.now(), respectShowHours: Boolean = true): String? {
        if (!cfg.configured) return "the camera isn't set up (preshow.lights)"
        if (fppHost.isNullOrBlank()) return "no FPP address"
        val local = now.atZone(zone)
        val showNight = props.firstShowNight == null || !local.toLocalDate().isBefore(props.firstShowNight)
        val quietStart = cfg.quietStart ?: props.showStart
        val t = local.toLocalTime()
        if (respectShowHours && showNight && !t.isBefore(quietStart) && t.isBefore(cfg.quietEnd)) return "it's show time (no light test from $quietStart to ${cfg.quietEnd})"
        val idle = try { lighter.idle(fppHost) } catch (e: Exception) { return "FPP isn't answering (${e.brief()})" }
        if (!idle) return "FPP is playing"
        return null
    }

    // ── Calibration ────────────────────────────────────────────────────────

    /**
     * Starts calibration in the background. Throws if busy, or with the reason lighting isn't allowed.
     * [missingOnly] relights only the models the saved calibration couldn't see (or doesn't know yet)
     * and merges them in: after a repair, in under a minute instead of five.
     */
    fun startCalibration(missingOnly: Boolean = false): LightTestStatus {
        if (missingOnly) check(calibration() != null) { "Can't recalibrate missing models: not calibrated yet" }
        val controllers = parser.parse(xlightsDir)
        val host = fppHost(controllers)
        blockedReason(host, respectShowHours = false)?.let { throw IllegalStateException("Can't calibrate: $it") }
        if (!lock.tryAcquire()) throw LightTestBusyException()
        abort.set(false)
        progress = LightTestStatus(busy = true, job = "calibration", message = "starting")
        Thread.ofVirtual().name("light-calibration").start {
            try {
                val cal = calibrate(controllers, host!!, missingOnly)
                progress = LightTestStatus(busy = false, job = "calibration", done = cal.models.size, total = cal.models.size,
                    message = "calibrated: ${cal.visible.size} of ${cal.models.size} models in the camera's view")
            } catch (e: Throwable) {
                // Throwable, not Exception: an out-of-memory error must still say "failed" on the page.
                log.warn("Light calibration failed", e)
                progress = progress.copy(busy = false, message = "calibration failed: ${e.brief()}")
            } finally {
                lock.release()
            }
        }
        return status()
    }

    internal fun calibrate(controllers: List<XLightsController>, host: String, missingOnly: Boolean = false): Calibration {
        val (all, skipped) = targets(controllers, host)
        val previous = if (missingOnly) calibration() else null
        val seen = previous?.models?.filter { it.visible }?.map { it.name }?.toSet() ?: emptySet()
        val targets = all.filter { it.name !in seen }
        require(targets.isNotEmpty()) {
            if (previous != null) "every model is already in view" else "no models to light (${skipped.values.firstOrNull() ?: "empty layout"})"
        }
        val candidates = mutableListOf<LightAnalysis.Candidate>()
        var size = 0 to 0
        var noise: BooleanArray? = null
        val staging = photos.begin("calibration")
        val composite = try {
            sweep(host, targets, "calibration", staging,
                onDarks = { a, b ->
                    val n = noise ?: BooleanArray(a.lum.size).also { noise = it }
                    LightMath.noise(a, b, cfg.maskThreshold.toFloat(), n)
                },
            ) { t, lit, base ->
                size = lit.width to lit.height
                candidates += LightAnalysis.candidate(t, lit, base, cfg)
            }
        } catch (e: Throwable) {
            photos.discard("calibration")
            throw e
        }
        if (previous != null) {
            check(previous.width == size.first && previous.height == size.second) {
                "the picture size changed since calibration (${previous.width}x${previous.height} -> ${size.first}x${size.second}): run a full calibration"
            }
            // Flicker seen on calibration night still counts, so it can't sneak into a new mask.
            val n = noise ?: BooleanArray(size.first * size.second).also { noise = it }
            previous.noise.forEach { if (it < n.size) n[it] = true }
        }
        // Masks only once every dark pair is in, so late flicker is removed from early models too.
        val fresh = candidates.map { LightAnalysis.calibrate(it, noise, size.first, cfg) }
        val cals = if (previous == null) fresh else merge(all.map { it.name }, fresh, previous.models)
        val cal = Calibration(Instant.now(), size.first, size.second, cfg.color, cals, skipped, noise?.let { Mask.of(it) } ?: Mask(emptyList()))
        Files.createDirectories(root)
        Files.writeString(calFile, objectMapper.writeValueAsString(cal))
        composite?.let { Files.write(calImage, LightAnalysis.annotate(LightAnalysis.toImage(it, size.first, size.second), cals, null, size.first)) }
        photos.commit("calibration", merge = previous != null)
        log.info("Light calibration saved ({}): {} of {} models visible", if (previous != null) "missing models only" else "full", cal.visible.size, cal.models.size)
        return cal
    }

    companion object {
        private val NULL_NAME = Regex("^null\\b", RegexOption.IGNORE_CASE)

        /** New calibrations replace old ones by name, in the layout's order; models no longer in the layout drop out. */
        internal fun merge(order: List<String>, fresh: List<ModelCal>, previous: List<ModelCal>): List<ModelCal> {
            val new = fresh.associateBy { it.name }
            val old = previous.associateBy { it.name }
            return order.mapNotNull { new[it] ?: old[it] }
        }
    }

    // ── Nightly test ───────────────────────────────────────────────────────

    /**
     * Lights every calibrated, visible model and grades it. Returns a run with
     * [LightRun.skippedBecause] set when lighting wasn't allowed. Saves it as the latest.
     */
    fun test(runId: String, controllers: List<XLightsController>, fppHost: String?, respectShowHours: Boolean = true): LightRun {
        val started = Instant.now()
        val cal = calibration()
        fun skipped(why: String) = LightRun(runId, started, Instant.now(), emptyList(), why, cal?.createdAt)
        if (cal == null) return skipped("not calibrated yet: run the calibration once after dark")
        blockedReason(fppHost, respectShowHours = respectShowHours)?.let { return skipped(it) }
        if (!lock.tryAcquire()) return skipped("a calibration or test is already running")
        try {
            abort.set(false)
            progress = LightTestStatus(busy = true, job = "test", message = "starting")
            val host = fppHost!!
            // Light by today's channels (an FPP Connect may have moved a board), keyed by model name.
            val (today, _) = targets(controllers, host)
            val byName = today.associateBy { it.name }
            val toTest = cal.visible.mapNotNull { c -> byName[c.name]?.let { c to it } }
            val signals = mutableMapOf<String, Signal>()
            val calByName = toTest.associate { it.second.name to it.first }
            val staging = photos.begin("test")
            val composite = try {
                sweep(host, toTest.map { it.second }, "test", staging) { t, lit, base ->
                    signals[t.name] = LightAnalysis.measure(calByName.getValue(t.name), lit, base, cfg)
                }
            } catch (e: Throwable) {
                photos.discard("test")
                throw e
            }
            // Models that vanished from the layout are reported as not in view rather than guessed at.
            val (results, typical) = LightAnalysis.classify(cal.models, signals, cfg)
            val run = LightRun(runId, started, Instant.now(), results, null, cal.createdAt, typical)
            save(run, composite?.let {
                LightAnalysis.annotate(LightAnalysis.toImage(it, cal.width, cal.height), cal.models,
                    results.associate { r -> r.name to r.status }, cal.width)
            })
            photos.commit("test")
            progress = LightTestStatus(busy = false, job = "test", done = toTest.size, total = toTest.size,
                message = "tested ${toTest.size} models: ${results.count { it.status == LightStatus.OFF }} off, ${results.count { it.status == LightStatus.DIM }} dim")
            return run
        } catch (e: Throwable) {
            progress = progress.copy(busy = false, message = "test failed: ${e.brief()}")
            throw e
        } finally {
            lock.release()
        }
    }

    private fun save(run: LightRun, image: ByteArray?) {
        Files.createDirectories(root)
        Files.writeString(lastRunFile, objectMapper.writeValueAsString(run))
        if (image != null) Files.write(lastRunImage, image) else Files.deleteIfExists(lastRunImage)
    }

    // ── Shared machinery ───────────────────────────────────────────────────

    fun fppHost(controllers: List<XLightsController>): String? =
        props.fpp.host.ifBlank { null } ?: controllers.firstOrNull { it.isFpp }?.ipAddress

    /** Every model on a show board, with its FPP channels and board output. */
    internal fun targets(controllers: List<XLightsController>, fppHost: String): Pair<List<ModelTarget>, Map<String, String>> {
        val starts = lighter.controllerStarts(fppHost)
        val fppIp = FppTester.resolve(fppHost)
        val targets = mutableListOf<ModelTarget>()
        val skipped = linkedMapOf<String, String>()
        for (c in controllers) {
            if (c.isFpp || c.ipAddress.isNullOrBlank()) continue
            if (props.wled.ignore.any { it.equals(c.name, ignoreCase = true) }) continue
            val ip = FppTester.resolve(c.ipAddress)
            if (ip == fppIp) continue
            // Null models are padding pixels between props: never part of the test, even when
            // they aren't in xLights' "Nulls" layout group.
            val models = c.models.filter {
                !it.isNull && !NULL_NAME.containsMatchIn(it.name) && it.channelCount > 0 &&
                    cfg.ignoreModels.none { n -> n.equals(it.name, ignoreCase = true) }
            }
            val start = starts[ip]
            if (start == null) {
                models.forEach { skipped[it.name] = "FPP has no output to ${c.name}" }
                continue
            }
            val busses = runCatching { wled.fetchDevice(c.ipAddress).busses }.getOrDefault(emptyList())
            val bpp = validation.channelsPerPixel(c)
            for (m in models) {
                val pixel = (m.startChannel - 1) / bpp
                val port = busses.indexOfFirst { pixel >= it.start && pixel < it.start + it.len }.takeIf { it >= 0 }?.plus(1)
                targets += ModelTarget(m.name, c.name, start + m.startChannel - 1, start + m.startChannel - 1 + m.channelCount - 1, port)
            }
        }
        return targets to skipped
    }

    /**
     * Lights each target in turn and hands its picture, plus a dark baseline (the brighter
     * of the all-off pictures before and after its block), to [onModel]. Returns the
     * brightest-of picture of everything that lit, for the report.
     */
    private fun sweep(
        host: String,
        targets: List<ModelTarget>,
        job: String,
        photoDir: Path? = null,
        onDarks: (Frame, Frame) -> Unit = { _, _ -> },
        onModel: (ModelTarget, Frame, Frame) -> Unit,
    ): IntArray? {
        Files.createDirectories(root)
        Files.writeString(activeMarker, host)
        var composite: IntArray? = null
        try {
            fun shot(): Frame {
                val f = Frame.decode(camera.snapshot(), cfg.analysisWidth, keepRgb = true)
                return f
            }
            fun dark(): Frame {
                lighter.stop(host)
                Thread.sleep(cfg.settleMs)
                return shot()
            }
            // FPP's first Test Start of a run shows up late (0.7 s after it, a lit model was still
            // mostly dark), which marked the first model "not in view". Warm it up once, and give
            // the first model double the settle time.
            targets.firstOrNull()?.let { lighter.light(host, it.firstChannel, it.lastChannel, cfg.color); Thread.sleep(cfg.settleMs * 2) }
            var before = dark()
            val block = mutableListOf<Pair<ModelTarget, Frame>>()
            for ((i, t) in targets.withIndex()) {
                if (abort.get()) throw LightTestAborted()
                progress = progress.copy(job = job, done = i, total = targets.size, message = "lighting ${t.name} (${t.where})")
                lighter.light(host, t.firstChannel, t.lastChannel, cfg.color)
                Thread.sleep(if (i == 0) cfg.settleMs * 2 else cfg.settleMs)
                val lit = shot()
                photoDir?.let { photos.save(it, t.name, lit) }
                composite = composite ?: IntArray(lit.width * lit.height)
                lit.rgb?.let { LightAnalysis.maxInto(composite!!, it) }
                block += t to Frame(lit.width, lit.height, lit.lum)
                if (block.size >= cfg.darkEvery.coerceAtLeast(1) || i == targets.lastIndex) {
                    val after = dark()
                    onDarks(before, after)
                    val base = Frame.max(before, after)
                    for ((bt, bf) in block) onModel(bt, bf, base)
                    block.clear()
                    before = after
                }
            }
        } finally {
            runCatching { lighter.stop(host) }.onFailure { log.error("Could not stop FPP test mode on {}: {}", host, it.brief()) }
            runCatching { Files.deleteIfExists(activeMarker) }
        }
        return composite
    }

    internal fun today(): LocalDate = LocalDate.now(zone)

}
