package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
    private val boards: ShowBoards,
) : PreShowChecker {
    override val layer = "Power"

    private companion object {
        const val NOMINAL_VOLTS = 120.0
        const val HISTORY = 30
        const val ON_KEY = "standbyAmpsOn"
        const val OFF_KEY = "standbyAmpsOff"
        /** The old single history, from before the boards were switched off and on each day. */
        const val LEGACY_KEY = "standbyAmps"
    }
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

        if (cfg.circuitSwitchEntity.isNotBlank()) {
            out += try {
                val state = ha.state(cfg.circuitSwitchEntity).path("state").asText("unknown")
                if (state == "on") CheckResult.pass(layer, "Show circuit relay", "Shelly", "on")
                else CheckResult.fail(layer, "Show circuit relay", "Shelly", "show circuit relay is $state: nothing on the circuit has power")
            } catch (e: Exception) {
                CheckResult.warn(layer, "Show circuit relay", "Shelly", "relay state not readable (${e.brief()})")
            }
        }

        if (cfg.currentEntity.isNotBlank()) {
            out += reading(cfg.currentEntity, "Show circuit current") { amps, _ -> currentResult(amps, ctx) }
        } else if (cfg.powerEntity.isNotBlank()) {
            out += reading(cfg.powerEntity, "Show circuit power") { watts, _ -> currentResult(watts / NOMINAL_VOLTS, ctx) }
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
     * Before the show the boards are black, so the current is the standby draw. Since
     * FPP switches the boards (and so their 12 V supplies) off at 10:15 PM and on at
     * 4 PM, what's normal depends on whether they're on: about 0.8 A off, about 4.4 A on
     * at Black. So the check reads the boards' on state and keeps a separate history
     * for each, and compares the reading with that history's median (and with a fixed
     * ceiling, when set), so a slowly failing supply or a stuck-on prop stands out.
     * With some boards on and some off there's no normal level to compare against, so
     * the reading is only reported. Manual runs aren't added to the history.
     */
    internal fun currentResult(amps: Double, ctx: CheckContext): CheckResult {
        if (!ctx.beforeShow) return CheckResult.pass(layer, "Current", "Shelly", "%.1f A".format(amps))
        if (cfg.maxStandbyAmps > 0 && amps > cfg.maxStandbyAmps) {
            return CheckResult.warn(layer, "Standby current", "Shelly", "%.1f A with the show black (expected under %.1f A)".format(amps, cfg.maxStandbyAmps))
        }
        val power = try {
            boards.power(ctx)
        } catch (e: Exception) {
            return CheckResult.pass(layer, "Standby current", "Shelly", "%.1f A (board on/off state not readable, not compared: ${e.brief()})".format(amps))
        }
        val (key, what) = when {
            power.allOn -> ON_KEY to "boards on"
            power.allOff -> OFF_KEY to "boards off"
            else -> return CheckResult.pass(layer, "Standby current", "Shelly",
                "%.1f A with %d of %d boards on (off: %s), not compared".format(amps, power.on, power.answering, power.offNames.joinToString()))
        }
        val history = store.state { st ->
            seedFromLegacy(st)
            val arr = (st.get(key) as? ArrayNode) ?: st.putArray(key)
            val before = arr.map { it.asDouble() }
            if (!ctx.manual) {
                arr.add(amps)
                while (arr.size() > HISTORY) arr.remove(0)
            }
            before
        }
        if (history.size >= 5) {
            val median = history.sorted()[history.size / 2]
            val limit = maxOf(median * 1.5, median + 0.5)
            if (amps > limit) {
                return CheckResult.warn(layer, "Standby current", "Shelly", "%.1f A with the show black and %s, usually %.1f A: something is lit or a supply is failing".format(amps, what, median))
            }
        }
        return CheckResult.pass(layer, "Standby current", "Shelly", "%.1f A (%s)".format(amps, what))
    }

    /**
     * One-time move from the old mixed history: readings of 3-6 A were boards on at
     * Black and readings under 1.2 A boards off. Test-day outliers in between or above
     * are dropped.
     */
    private fun seedFromLegacy(st: ObjectNode) {
        val legacy = st.get(LEGACY_KEY) as? ArrayNode ?: return
        val values = legacy.map { it.asDouble() }
        if (st.get(ON_KEY) == null) st.putArray(ON_KEY).also { a -> values.filter { it in 3.0..6.0 }.takeLast(HISTORY).forEach { a.add(it) } }
        if (st.get(OFF_KEY) == null) st.putArray(OFF_KEY).also { a -> values.filter { it < 1.2 }.takeLast(HISTORY).forEach { a.add(it) } }
        st.remove(LEGACY_KEY)
    }
}
