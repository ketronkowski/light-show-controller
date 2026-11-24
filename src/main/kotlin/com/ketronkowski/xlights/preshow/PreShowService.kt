package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.exists
import kotlin.io.path.readText

/** How a run reports: push and email always, only on trouble, or not at all. */
data class NotifyPolicy(val pushAlways: Boolean, val emailAlways: Boolean, val quiet: Boolean = false) {
    companion object {
        val QUIET = NotifyPolicy(pushAlways = false, emailAlways = false, quiet = true)
        val ALWAYS = NotifyPolicy(pushAlways = true, emailAlways = true)
    }
}

class PreShowBusyException : RuntimeException("a pre-show check is already running")

/**
 * Runs every checker, decides GO / GO with warnings / NO-GO, saves the run, updates
 * the Home Assistant status sensor and sends the push and the email.
 */
@Service
class PreShowService(
    private val checkers: List<PreShowChecker>,
    private val parser: XLightsConfigParser,
    private val store: PreShowStore,
    private val ha: HomeAssistantClient,
    private val mailer: PreShowMailer,
    private val props: PreShowProperties,
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(PreShowService::class.java)
    private val xlightsDir: Path = Path.of(dataDir, "xlights")
    private val running = ReentrantLock()
    private val zone get() = ZoneId.of(props.zone)

    fun run(pass: String, policy: NotifyPolicy, layers: List<String> = emptyList(), manual: Boolean = false): PreShowRun {
        if (!running.tryLock()) throw PreShowBusyException()
        try {
            val run = execute(pass, layers, manual)
            store.save(run)
            store.prune(props.keepDays)
            report(run, policy)
            return run
        } finally {
            running.unlock()
        }
    }

    val busy: Boolean get() = running.isLocked

    internal fun execute(pass: String, layers: List<String> = emptyList(), manual: Boolean = false): PreShowRun {
        val started = Instant.now()
        val id = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zone).format(started) + "-" + pass.replace(Regex("[^A-Za-z0-9-]"), "")
        val selected = if (layers.isEmpty()) checkers
            else checkers.filter { c -> layers.any { it.equals(c.layer, ignoreCase = true) } }
        val setup = mutableListOf<CheckResult>()
        val controllers: List<XLightsController> = try {
            parser.parse(xlightsDir)
        } catch (e: Exception) {
            setup += CheckResult.fail("Setup", "xLights layout", "app", "no usable xLights layout uploaded (${e.brief()}): upload the show folder in the app")
            emptyList()
        }
        val uploadedAt = uploadedAt()
        if (controllers.isNotEmpty()) {
            setup += CheckResult.pass("Setup", "xLights layout", "app", "${controllers.size} controllers in the uploaded layout")
        }

        val ctx = CheckContext(
            controllers = controllers,
            showDir = xlightsDir,
            now = started,
            zone = zone,
            showStart = props.showStart,
            firstShowNight = props.firstShowNight,
            fppHost = props.fpp.host.ifBlank { null } ?: controllers.firstOrNull { it.isFpp }?.ipAddress,
            runId = id,
            layers = layers.toSet(),
            manual = manual,
        )

        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val results = try {
            selected.map { c -> c to CompletableFuture.supplyAsync({ c.check(ctx) }, pool) }
                .flatMap { (c, f) ->
                    try {
                        f.get(c.timeoutMinutes, TimeUnit.MINUTES)
                    } catch (e: Exception) {
                        log.warn("Pre-show checker {} failed", c.layer, e)
                        listOf(CheckResult.fail(c.layer, "Check ran", c.layer, "the ${c.layer} check crashed or timed out (${(e.cause ?: e).brief()})"))
                    }
                }
        } finally {
            pool.shutdownNow()
        }

        val all = setup + results
        val finished = Instant.now()
        return PreShowRun(
            id = id,
            pass = pass,
            startedAt = started,
            finishedAt = finished,
            verdict = PreShowRun.verdictOf(all),
            results = all,
            layoutUploadedAt = uploadedAt,
        )
    }

    private fun report(run: PreShowRun, policy: NotifyPolicy) {
        log.info("Pre-show {} finished: {}", run.id, run.headline())
        publishToHa(run)
        if (policy.quiet) return
        val trouble = run.verdict == Verdict.NO_GO
        if (policy.pushAlways || trouble) push(run)
        if (policy.emailAlways || trouble) mailer.send(run)
    }

    private fun push(run: PreShowRun) {
        val title = "Pre-show ${run.pass}: ${run.verdict.label}"
        val body = buildString {
            append(run.headline())
            val top = run.topProblems()
            if (top.isNotEmpty()) append("\n").append(top.joinToString("\n"))
        }
        val url = props.ha.reportUrl.ifBlank { null }?.let { "${it.trimEnd('/')}/preshow.html?run=${run.id}" }
        ha.notify(title, body, url)
    }

    private fun publishToHa(run: PreShowRun) {
        if (!ha.configured || props.ha.statusEntity.isBlank()) return
        runCatching {
            ha.setState(props.ha.statusEntity, run.verdict.name, mapOf(
                "friendly_name" to "Pre-show status",
                "icon" to "mdi:clipboard-check-outline",
                "pass" to run.pass,
                "run_id" to run.id,
                "headline" to run.headline(),
                "finished_at" to run.finishedAt.toString(),
                "failures" to run.failures.size,
                "warnings" to run.warnings.size,
                "problems" to run.topProblems(10),
            ))
        }.onFailure { log.warn("Could not update {} in Home Assistant: {}", props.ha.statusEntity, it.brief()) }
    }

    private fun uploadedAt(): Instant? {
        val meta = xlightsDir.resolve("upload-meta.json")
        if (!meta.exists()) return null
        return runCatching { Instant.parse(objectMapper.readTree(meta.readText()).path("uploadedAt").asText()) }.getOrNull()
    }
}
