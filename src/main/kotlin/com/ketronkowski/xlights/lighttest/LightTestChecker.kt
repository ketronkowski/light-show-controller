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
            val run = service.test(ctx.runId, ctx.controllers, ctx.fppHost, respectShowHours = !ctx.manual)
            run.skippedBecause?.let { return listOf(CheckResult.warn(layer, "Camera light test", "camera", "light test skipped: $it")) }
            service.lastRunImage()?.let { store.saveAttachment(ctx.runId, LIGHTS_IMAGE, it) }
            return results(run, null, props.lights.softModels)
        }
        val last = service.lastRun()
        val age = last?.let { Duration.between(it.finishedAt, ctx.now) }
        if (last == null || last.skippedBecause != null || age == null || age.isNegative || age.toHours() >= props.lights.resultMaxAgeHours) {
            return listOf(CheckResult.skip(layer, "Camera light test", "camera", "no camera light test in the last ${props.lights.resultMaxAgeHours} h"))
        }
        service.lastRunImage()?.let { store.saveAttachment(ctx.runId, LIGHTS_IMAGE, it) }
        val time = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.of(props.zone)).format(last.startedAt)
        return results(last, "from the $time camera test", props.lights.softModels)
    }

    companion object {
        const val LIGHTS_IMAGE = "lights.jpg"
        private const val LAYER = "Lights"

        /**
         * "Does it light" is the question: a model either lit or it didn't. Dim is only a note.
         * One row per problem, rolled up so a dead board, port or wiring break is one line, not
         * forty: a whole controller dark, then a whole port dark, then a port dark from one
         * model on (a data break), then single models. [softModels] (name patterns) may hide
         * behind other props, so one of them not lighting is a note unless its whole port is dark.
         */
        fun results(run: LightRun, note: String?, softModels: List<String> = emptyList()): List<CheckResult> {
            val suffix = note?.let { " ($it)" } ?: ""
            val soft = softModels.map { globRegex(it) }
            fun isSoft(m: ModelResult) = soft.any { it.matches(m.name) }
            val tested = run.results.filter { it.status != LightStatus.NOT_IN_VIEW }
            if (tested.isEmpty()) return listOf(CheckResult.warn(LAYER, "Camera light test", "camera", "no models in the camera's view$suffix"))
            val off = tested.filter { it.status == LightStatus.OFF }
            if (off.size >= tested.size * 0.8 && tested.size >= 5) {
                return listOf(CheckResult.fail(LAYER, "Camera light test", "camera",
                    "${off.size} of ${tested.size} models didn't light: the show has no power or data, or the camera moved since calibration$suffix"))
            }
            val out = mutableListOf<CheckResult>()
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
                        continue
                    }
                    // A data break: everything after one point on the port is dark (hidden soft models aside).
                    val wired = onPort.filter { !isSoft(it) && it.firstChannel != null }.sortedBy { it.firstChannel }
                    val lastLit = wired.indexOfLast { it.status != LightStatus.OFF }
                    val tail = if (lastLit >= 0) wired.drop(lastLit + 1) else emptyList()
                    if (tail.size >= 2) {
                        out += CheckResult.fail(LAYER, "Port lit", "$ctrl port $port",
                            "dark from ${tail.first().name} on (${tail.size} models): data break between ${wired[lastLit].name} and ${tail.first().name}$suffix")
                        covered += tail.map { it.name }
                    }
                }
            }
            val hidden = off.filter { it.name !in covered && isSoft(it) }
            for (m in off.filter { it.name !in covered && !isSoft(it) }) {
                out += CheckResult.fail(LAYER, "Model lit", m.name, "didn't light (${m.where})$suffix")
            }
            val lit = tested.size - off.size
            val dim = tested.count { it.status == LightStatus.DIM }
            val notInView = run.results.size - tested.size
            val notes = buildList {
                if (notInView > 0) add("$notInView not in the camera's view")
                if (hidden.isNotEmpty()) add("not seen, may be hidden: ${hidden.joinToString { it.name }}")
                if (dim > 0) add("$dim dimmer than at calibration (twilight or part of a string; not a failure)")
            }
            out.add(0, CheckResult.pass(LAYER, "Camera light test", "camera",
                "$lit of ${tested.size} models lit" + notes.joinToString("") { "; $it" } + suffix))
            return out
        }

        /** `PeaceStake-*` style name pattern, ignoring case. */
        internal fun globRegex(glob: String): Regex =
            Regex(glob.split("*").joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    }
}
