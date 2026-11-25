package com.ketronkowski.xlights.jukebox

import com.ketronkowski.xlights.preshow.CheckContext
import com.ketronkowski.xlights.preshow.CheckResult
import com.ketronkowski.xlights.preshow.PreShowChecker
import com.ketronkowski.xlights.preshow.PreShowProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Jukebox" layer. In the jukebox pass (layers: [Jukebox]) it plays the interrupt test:
 * a Remote Falcon request cuts into Background, a second one waits its turn, and Background
 * resumes where it paused. The other passes repeat the latest result, so the 4:50 PM
 * GO / NO-GO includes it without playing anything. Problems are warnings: the show still
 * runs, requests just behave differently.
 */
@Component
class JukeboxChecker(
    private val service: JukeboxService,
    private val props: PreShowProperties,
) : PreShowChecker {
    override val layer = LAYER
    override val timeoutMinutes: Long = 6

    override fun check(ctx: CheckContext): List<CheckResult> {
        if (!props.jukebox.configured) return emptyList()
        if (ctx.requested(layer)) {
            val run = service.test(ctx.fppHost, respectShowHours = !ctx.manual)
            return results(run, null)
        }
        val last = service.lastRun()
        val age = last?.let { Duration.between(it.finishedAt, ctx.now) }
        if (last == null || last.skippedBecause != null || age == null || age.isNegative || age.toHours() >= props.jukebox.resultMaxAgeHours) {
            return listOf(CheckResult.skip(layer, "Jukebox interrupt", "Remote Falcon", "no jukebox test in the last ${props.jukebox.resultMaxAgeHours} h"))
        }
        val time = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.of(props.zone)).format(last.startedAt)
        return results(last, "from the $time jukebox test")
    }

    companion object {
        const val LAYER = "Jukebox"
        private const val TARGET = "Remote Falcon"

        fun results(run: JukeboxRun, note: String?): List<CheckResult> {
            val suffix = note?.let { " ($it)" } ?: ""
            run.skippedBecause?.let { return listOf(CheckResult.warn(LAYER, "Jukebox interrupt", TARGET, "jukebox test skipped: $it$suffix")) }
            val out = run.steps.map {
                if (it.ok) CheckResult.pass(LAYER, it.name, TARGET, it.message + suffix)
                else CheckResult.warn(LAYER, it.name, TARGET, it.message + suffix)
            }.toMutableList()
            // Cleanup failing can leave FPP playing or muted at show time: that one is a failure.
            run.cleanupError?.let { out += CheckResult.fail(LAYER, "Cleanup", "FPP", "after the jukebox test, couldn't $it: check FPP before the show$suffix") }
            return out
        }
    }
}
