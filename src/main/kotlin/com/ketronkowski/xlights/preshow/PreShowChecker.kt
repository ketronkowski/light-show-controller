package com.ketronkowski.xlights.preshow

import com.ketronkowski.xlights.domain.XLightsController
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What every check gets: the expected layout from xLights and the time of the run. */
data class CheckContext(
    val controllers: List<XLightsController>,
    val showDir: Path,
    val now: Instant,
    val zone: ZoneId,
    val showStart: LocalTime,
    /** FPP's address, from settings or the xLights layout. */
    val fppHost: String?,
    /** First real show night. Before it, a missing show schedule is only a warning (dry runs). */
    val firstShowNight: LocalDate? = null,
    /** This run's id, for files a check saves next to the run (e.g. the light test picture). */
    val runId: String = "",
    /** Layers this pass asked for by name. Empty = a normal pass with every layer. */
    val layers: Set<String> = emptySet(),
    /** Started by a person from the page or API, not by the schedule. */
    val manual: Boolean = false,
) {
    /** True when this pass asked for [layer] by name (e.g. the camera pass for "Lights"). */
    fun requested(layer: String): Boolean = layers.any { it.equals(layer, ignoreCase = true) }
    val localNow: ZonedDateTime get() = now.atZone(zone)
    /** Before tonight's show start (pre-show passes), as opposed to during or after it. */
    val beforeShow: Boolean get() = localNow.toLocalTime().isBefore(showStart)
    /** False on dry-run days before the first show night. */
    val showNight: Boolean get() = firstShowNight == null || !localNow.toLocalDate().isBefore(firstShowNight)
}

/** One layer of the pre-show check (boards, FPP, power, web, ...). */
interface PreShowChecker {
    val layer: String
    fun check(ctx: CheckContext): List<CheckResult>
    /** How long this layer may take before the run gives up on it. */
    val timeoutMinutes: Long get() = 4
}

/** Human-friendly duration: "45 min", "5 h 10 min", "3 d 4 h". */
fun humanDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val d = s / 86_400
    val h = (s % 86_400) / 3_600
    val m = (s % 3_600) / 60
    return when {
        d > 0 -> "$d d $h h"
        h > 0 -> "$h h $m min"
        else -> "$m min"
    }
}
