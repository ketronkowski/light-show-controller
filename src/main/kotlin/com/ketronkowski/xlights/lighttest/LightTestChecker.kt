package com.ketronkowski.xlights.lighttest

import com.ketronkowski.xlights.preshow.CheckContext
import com.ketronkowski.xlights.preshow.CheckResult
import com.ketronkowski.xlights.preshow.PreShowChecker
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.PreShowStore
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Lights" layer. In the camera pass (layers: [Lights]) it lights every model and grades
 * it with the camera. In the other passes it repeats the day's camera result, so the
 * 4:50 PM GO / NO-GO includes it without lighting anything again.
 */
@Component
class LightTestChecker(
    private val service: LightTestService,
    private val store: PreShowStore,
    private val props: PreShowProperties,
) : PreShowChecker {
    override val layer = "Lights"
    override val timeoutMinutes: Long = 15

    override fun check(ctx: CheckContext): List<CheckResult> {
        if (!props.lights.configured) return emptyList()
        if (ctx.requested(layer)) {
            val run = service.test(ctx.runId, ctx.controllers, ctx.fppHost)
            run.skippedBecause?.let { return listOf(CheckResult.warn(layer, "Camera light test", "camera", "light test skipped: $it")) }
            service.lastRunImage()?.let { store.saveAttachment(ctx.runId, LIGHTS_IMAGE, it) }
            return results(run, null)
        }
        val last = service.lastRun()
        val age = last?.let { Duration.between(it.finishedAt, ctx.now) }
        if (last == null || last.skippedBecause != null || age == null || age.isNegative || age.toHours() >= props.lights.resultMaxAgeHours) {
            return listOf(CheckResult.skip(layer, "Camera light test", "camera", "no camera light test in the last ${props.lights.resultMaxAgeHours} h"))
        }
        service.lastRunImage()?.let { store.saveAttachment(ctx.runId, LIGHTS_IMAGE, it) }
        val time = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.of(props.zone)).format(last.startedAt)
        return results(last, "from the $time camera test")
    }

    companion object {
        const val LIGHTS_IMAGE = "lights.jpg"
        private const val LAYER = "Lights"

        /**
         * One row per problem, rolled up so a dead board or port is one line, not forty:
         * a whole controller dark, then a whole port dark, then single models.
         */
        fun results(run: LightRun, note: String?): List<CheckResult> {
            val suffix = note?.let { " ($it)" } ?: ""
            val tested = run.results.filter { it.status != LightStatus.NOT_IN_VIEW }
            val out = mutableListOf<CheckResult>()
            if (tested.isEmpty()) return listOf(CheckResult.warn(LAYER, "Camera light test", "camera", "no models in the camera's view$suffix"))
            val off = tested.filter { it.status == LightStatus.OFF }
            if (off.size >= tested.size * 0.8 && tested.size >= 5) {
                out += CheckResult.fail(LAYER, "Camera light test", "camera",
                    "${off.size} of ${tested.size} models didn't light: the show has no power or data, or the camera moved since calibration$suffix")
                return out
            }
            val covered = mutableSetOf<String>()
            for ((ctrl, models) in tested.groupBy { it.controller }) {
                if (models.size >= 2 && models.all { it.status == LightStatus.OFF }) {
                    out += CheckResult.fail(LAYER, "Board lit", ctrl, "none of its ${models.size} models in view lit: board, power or data problem$suffix")
                    covered += models.map { it.name }
                    continue
                }
                for ((port, onPort) in models.filter { it.port != null }.groupBy { it.port }) {
                    if (onPort.size >= 2 && onPort.all { it.status == LightStatus.OFF }) {
                        out += CheckResult.fail(LAYER, "Port lit", "$ctrl port $port",
                            "none of its ${onPort.size} models lit: port, fuse, power injection or first pixel$suffix")
                        covered += onPort.map { it.name }
                    }
                }
            }
            for (m in off.filter { it.name !in covered }) {
                out += CheckResult.fail(LAYER, "Model lit", m.name, "didn't light (${m.where})$suffix")
            }
            for (m in tested.filter { it.status == LightStatus.DIM }) {
                out += CheckResult.warn(LAYER, "Model lit", m.name,
                    "dimmer or smaller than at calibration (${pct(m.level)} brightness, ${pct(m.area)} of its area; ${m.where}): part of the string may be out$suffix")
            }
            val on = tested.count { it.status == LightStatus.ON }
            val notInView = run.results.size - tested.size
            out.add(0, CheckResult.pass(LAYER, "Camera light test", "camera",
                "$on of ${tested.size} models lit" + (if (notInView > 0) "; $notInView not in the camera's view" else "") + suffix))
            return out
        }

        private fun pct(v: Double?) = v?.let { "${Math.round(it * 100)}%" } ?: "?"
    }
}
