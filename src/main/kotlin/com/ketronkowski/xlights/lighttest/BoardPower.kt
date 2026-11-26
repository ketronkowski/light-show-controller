package com.ketronkowski.xlights.lighttest

import com.ketronkowski.xlights.preshow.brief

/** A WLED board's on/off switch (on Kevin's boards it also switches the pixel power supply). */
interface BoardSwitch {
    fun isOn(ip: String): Boolean
    /** On, on the boot preset (1 = Black), so nothing lights until FPP sends data. */
    fun turnOn(ip: String)
    fun turnOff(ip: String)
}

/**
 * Makes sure every board in a light test is on, and puts back the ones that were off.
 * A board that can't be read or switched is left alone and reported; the test still runs.
 */
class BoardPower(private val switch: BoardSwitch, private val sleep: (Long) -> Unit) {
    data class PowerUp(val switchedOn: List<String>, val errors: Map<String, String>)

    /**
     * Switches on every board in [ips] that is off and, if any was, waits [waitMs] for the
     * supplies to come up. [onSwitched] gets the list after each switch-on, so a crash
     * mid-way still knows which boards to switch back off.
     */
    fun powerUp(ips: List<String>, waitMs: Long, onSwitched: (List<String>) -> Unit = {}): PowerUp {
        val on = mutableListOf<String>()
        val errors = linkedMapOf<String, String>()
        for (ip in ips) {
            val wasOn = try { switch.isOn(ip) } catch (e: Exception) { errors[ip] = "couldn't read: ${e.brief()}"; continue }
            if (wasOn) continue
            try {
                switch.turnOn(ip)
                on += ip
                onSwitched(on.toList())
            } catch (e: Exception) {
                errors[ip] = "couldn't switch on: ${e.brief()}"
            }
        }
        if (on.isNotEmpty()) sleep(waitMs)
        return PowerUp(on, errors)
    }

    /** Switches [ips] back off. Returns the ones that failed, with why. */
    fun restore(ips: List<String>): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        for (ip in ips) runCatching { switch.turnOff(ip) }.onFailure { errors[ip] = it.brief() }
        return errors
    }
}
