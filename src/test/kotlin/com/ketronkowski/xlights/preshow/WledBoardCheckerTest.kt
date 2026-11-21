package com.ketronkowski.xlights.preshow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class WledBoardCheckerTest {

    private val checker = WledBoardChecker(http(), mock(), PreShowProperties())

    private fun seen(name: String, uptimeSeconds: Long) =
        WledBoardChecker.Seen(WledBoardChecker.Board(name, "10.0.0.1"), json("""{"uptime":$uptimeSeconds}"""), null, null)

    @Test
    fun `boards that came up together are one circuit power-up, a lone restart warns`() {
        val now = at(14)
        val boards = (1..8).map { seen("Board$it", 6 * 3600L + it * 30) } + seen("Mailbox", 1800) + seen("Old", 5 * 86_400L)
        val r = checker.restartChecks(boards, now)
        assertEquals(2, r.size)
        assertEquals(Outcome.PASS, r[0].outcome)
        assertTrue(r[0].message.startsWith("powered up together"), r[0].message)
        assertEquals("Mailbox", r[1].target)
        assertEquals(Outcome.WARN, r[1].outcome)
    }

    @Test
    fun `no recent restarts reports nothing`() {
        assertEquals(emptyList<CheckResult>(), checker.restartChecks(listOf(seen("A", 3 * 86_400L)), at(14)))
    }

    @Test
    fun `two lone restarts both warn`() {
        val r = checker.restartChecks(listOf(seen("A", 600), seen("B", 20_000), seen("C", 9 * 86_400L)), at(14))
        assertEquals(listOf("A", "B"), r.map { it.target })
        assertTrue(r.all { it.outcome == Outcome.WARN })
    }

    @Test
    fun `only a visibly lit board counts, not one that is merely off its boot preset`() {
        val black = """{"on":true,"bri":128,"ps":-1,"seg":[{"on":true,"fx":0,"col":[[0,0,0],[0,0,0],[0,0,0]]},{"on":false,"fx":0,"col":[[255,0,0]]}]}"""
        assertEquals(0, checker.litSegments(json(black)))
        val lit = """{"on":true,"bri":128,"ps":-1,"seg":[{"on":true,"fx":0,"col":[[255,160,0],[0,0,0]]},{"on":true,"fx":9,"col":[[0,0,0]]},{"on":true,"fx":0,"col":[[0,0,0]]}]}"""
        assertEquals(2, checker.litSegments(json(lit)))
        val masterOff = """{"on":false,"bri":128,"seg":[{"on":true,"fx":9,"col":[[255,0,0]]}]}"""
        assertEquals(0, checker.litSegments(json(masterOff)))
    }
}
