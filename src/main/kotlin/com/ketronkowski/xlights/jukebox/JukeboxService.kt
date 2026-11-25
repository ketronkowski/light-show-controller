package com.ketronkowski.xlights.jukebox

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.preshow.JsonHttp
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.brief
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Semaphore
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Runs the jukebox interrupt test ([JukeboxTester]) against FPP and Remote Falcon, one at a
 * time, and keeps the latest result so the other passes can report it without playing again.
 */
@Service
class JukeboxService(
    private val props: PreShowProperties,
    private val objectMapper: ObjectMapper,
    dataDir: String,
    private val rigFor: (fppHost: String) -> JukeboxRig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    @Autowired
    constructor(
        props: PreShowProperties,
        objectMapper: ObjectMapper,
        http: JsonHttp,
        @Value("\${xlights.data-dir}") dataDir: String,
        @Value("\${xlights.fpp.username:admin}") fppUser: String,
        @Value("\${xlights.fpp.password:}") fppPassword: String,
    ) : this(props, objectMapper, dataDir, { host -> FppJukeboxRig(http, host, props.jukebox, fppUser, fppPassword) })

    private val log = LoggerFactory.getLogger(JukeboxService::class.java)
    private val lastFile: Path = Path.of(dataDir, "jukebox", "last.json")
    private val lock = Semaphore(1)
    private val cfg get() = props.jukebox

    val busy: Boolean get() = lock.availablePermits() == 0

    fun lastRun(): JukeboxRun? =
        if (lastFile.exists()) runCatching { objectMapper.readValue(lastFile.readText(), JukeboxRun::class.java) }.getOrNull() else null

    /**
     * Why the test can't play now, or null when it can. A scheduled run must start at least
     * [PreShowProperties.JukeboxTest.latestStartMinutes] before the show so it never overlaps
     * FPP starting the show (its cleanup stops FPP). A person starting it by hand has stopped
     * the show on purpose, so only "FPP is idle" applies then.
     */
    fun blockedReason(rig: JukeboxRig, now: Instant, respectShowHours: Boolean): String? {
        if (!cfg.configured) return "Remote Falcon isn't set up for it (preshow.jukebox)"
        if (respectShowHours) {
            val local = now.atZone(ZoneId.of(props.zone))
            val showNight = props.firstShowNight == null || !local.toLocalDate().isBefore(props.firstShowNight)
            val latest = props.showStart.minusMinutes(cfg.latestStartMinutes)
            if (showNight && !local.toLocalTime().isBefore(latest)) return "too close to show time (scheduled runs must start before $latest)"
        }
        val state = try { rig.state() } catch (e: Exception) { return "FPP isn't answering (${e.brief()})" }
        if (state.playing) return "FPP is playing ${state.playlist ?: "something"}"
        return null
    }

    /** Plays the test now. Never throws: problems come back in the run. */
    fun test(fppHost: String?, respectShowHours: Boolean = true): JukeboxRun {
        val now = Instant.ofEpochMilli(clock())
        fun skipped(why: String) = JukeboxRun(now, now, emptyList(), skippedBecause = why)
        if (fppHost.isNullOrBlank()) return skipped("no FPP address")
        if (!lock.tryAcquire()) return skipped("the jukebox test is already running")
        try {
            val rig = rigFor(fppHost)
            blockedReason(rig, now, respectShowHours)?.let { return skipped(it) }
            log.info("Jukebox test starting on {}", fppHost)
            val run = JukeboxTester(rig, cfg, clock, sleep).run()
            log.info("Jukebox test finished: ok={} steps={} cleanup={}", run.ok, run.steps.map { "${it.name}=${it.ok}" }, run.cleanupError)
            save(run)
            return run
        } finally {
            lock.release()
        }
    }

    private fun save(run: JukeboxRun) {
        runCatching {
            Files.createDirectories(lastFile.parent)
            val tmp = lastFile.resolveSibling("last.json.tmp")
            Files.writeString(tmp, objectMapper.writeValueAsString(run))
            Files.move(tmp, lastFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { log.warn("Could not save the jukebox test result: {}", it.brief()) }
    }
}
