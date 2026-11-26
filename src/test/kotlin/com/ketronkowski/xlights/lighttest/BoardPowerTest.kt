package com.ketronkowski.xlights.lighttest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BoardPowerTest {
    private class Fake(on: Set<String>, val broken: Set<String> = emptySet()) : BoardSwitch {
        val state = mutableMapOf<String, Boolean>().apply { listOf("a", "b", "c", "d").forEach { put(it, it in on) } }
        val calls = mutableListOf<String>()
        override fun isOn(ip: String): Boolean { if (ip in broken) error("timed out"); return state.getValue(ip) }
        override fun turnOn(ip: String) { calls += "on $ip"; state[ip] = true }
        override fun turnOff(ip: String) { calls += "off $ip"; state[ip] = false }
    }

    @Test
    fun `switches on only the boards that are off, waits once, and puts them back`() {
        val sw = Fake(on = setOf("a", "c"))
        val slept = mutableListOf<Long>()
        val seen = mutableListOf<List<String>>()
        val power = BoardPower(sw) { slept += it }
        val up = power.powerUp(listOf("a", "b", "c", "d"), 5000) { seen += it }
        assertThat(up.switchedOn).containsExactly("b", "d")
        assertThat(slept).containsExactly(5000L)
        assertThat(seen.last()).containsExactly("b", "d")
        assertThat(power.restore(up.switchedOn)).isEmpty()
        assertThat(sw.state).containsEntry("a", true).containsEntry("b", false).containsEntry("c", true).containsEntry("d", false)
    }

    @Test
    fun `all on means no switching and no wait`() {
        val sw = Fake(on = setOf("a", "b"))
        val slept = mutableListOf<Long>()
        val up = BoardPower(sw) { slept += it }.powerUp(listOf("a", "b"), 5000)
        assertThat(up.switchedOn).isEmpty()
        assertThat(slept).isEmpty()
        assertThat(sw.calls).isEmpty()
    }

    @Test
    fun `a board that can't be read is reported and left alone`() {
        val sw = Fake(on = emptySet(), broken = setOf("b"))
        val up = BoardPower(sw) {}.powerUp(listOf("a", "b"), 0)
        assertThat(up.switchedOn).containsExactly("a")
        assertThat(up.errors.keys).containsExactly("b")
    }
}
