package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.node.ArrayNode
import org.springframework.stereotype.Component

/**
 * Power checks through Home Assistant: the show circuit Shelly (voltage, standby
 * current) and the smart plugs feeding show boards. Read-only.
 */
@Component
class PowerChecker(
    private val ha: HomeAssistantClient,
    private val props: PreShowProperties,
    private val store: PreShowStore,
) : PreShowChecker {
    override val layer = "Power"
    private val cfg get() = props.ha

    override fun check(ctx: CheckContext): List<CheckResult> {
        if (!ha.configured) return listOf(CheckResult.skip(layer, "Home Assistant", "HA", "Home Assistant is not configured"))
        val out = mutableListOf<CheckResult>()

        if (cfg.voltageEntity.isNotBlank()) {
            out += reading(cfg.voltageEntity, "Show circuit voltage") { v, unit ->
                if (v < cfg.minVoltage) CheckResult.fail(layer, "Show circuit", "Shelly", "show circuit at %.0f %s: breaker or GFCI tripped?".format(v, unit))
                else CheckResult.pass(layer, "Show circuit", "Shelly", "%.0f %s".format(v, unit))
            }
        }

        if (cfg.currentEntity.isNotBlank()) {
            out += reading(cfg.currentEntity, "Show circuit current") { amps, _ -> currentResult(amps, ctx) }
        }

        for ((label, entity) in cfg.plugs) {
            out += try {
                val state = ha.state(entity).path("state").asText("unknown")
                if (state == "on") CheckResult.pass(layer, "Smart plug", label, "on")
                else CheckResult.fail(layer, "Smart plug", label, "plug is $state: board has no power")
            } catch (e: Exception) {
                CheckResult.fail(layer, "Smart plug", label, "plug state not readable (${e.brief()})")
            }
        }
        return out
    }

    private fun reading(entity: String, what: String, judge: (Double, String) -> CheckResult): CheckResult =
        try {
            val node = ha.state(entity)
            val state = node.path("state").asText("")
            val unit = node.path("attributes").path("unit_of_measurement").asText("")
            state.toDoubleOrNull()?.let { judge(it, unit) }
                ?: CheckResult.fail(layer, what, "Shelly", "Shelly reads \"$state\" (unavailable?)")
        } catch (e: Exception) {
            CheckResult.warn(layer, what, "Shelly", "not readable from Home Assistant (${e.brief()})")
        }

    /**
     * Before the show the boards are black, so the current is the standby draw.
     * It is compared with a fixed ceiling (when set) and with the median of earlier
     * pre-show readings, so a slowly failing supply or a stuck-on prop stands out.
     */
    internal fun currentResult(amps: Double, ctx: CheckContext): CheckResult {
        if (!ctx.beforeShow) return CheckResult.pass(layer, "Current", "Shelly", "%.1f A".format(amps))
        val history = store.state { st ->
            val arr = (st.get("standbyAmps") as? ArrayNode) ?: st.putArray("standbyAmps")
            val before = arr.map { it.asDouble() }
            arr.add(amps)
            while (arr.size() > 30) arr.remove(0)
            before
        }
        if (cfg.maxStandbyAmps > 0 && amps > cfg.maxStandbyAmps) {
            return CheckResult.warn(layer, "Standby current", "Shelly", "%.1f A with the show black (expected under %.1f A)".format(amps, cfg.maxStandbyAmps))
        }
        if (history.size >= 5) {
            val median = history.sorted()[history.size / 2]
            val limit = maxOf(median * 1.5, median + 0.5)
            if (amps > limit) {
                return CheckResult.warn(layer, "Standby current", "Shelly", "%.1f A with the show black, usually %.1f A: something is lit or a supply is failing".format(amps, median))
            }
        }
        return CheckResult.pass(layer, "Standby current", "Shelly", "%.1f A".format(amps))
    }
}
