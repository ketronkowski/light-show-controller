package com.ketronkowski.xlights.preshow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PreShowRunTest {

    private fun run(vararg r: CheckResult) =
        PreShowRun("id", "final", Instant.EPOCH, Instant.EPOCH, PreShowRun.verdictOf(r.toList()), r.toList())

    @Test
    fun `any blocker makes it NO-GO, warnings alone are GO with warnings, skips do not count`() {
        val pass = CheckResult.pass("Boards", "Answering", "Octa5")
        val warn = CheckResult.warn("Boards", "Firmware", "Octa6", "firmware 0.15")
        val fail = CheckResult.fail("FPP", "fppd", "FPP", "not running")
        val skip = CheckResult.skip("Power", "HA", "HA", "not configured")

        assertEquals(Verdict.GO, run(pass, skip).verdict)
        assertEquals("GO: all 1 checks passed", run(pass, skip).headline())
        assertEquals(Verdict.GO_WITH_WARNINGS, run(pass, warn).verdict)
        assertEquals(Verdict.NO_GO, run(pass, warn, fail).verdict)
        assertEquals("NO-GO: 1 blocker, 1 warning", run(pass, warn, fail).headline())
    }

    @Test
    fun `top problems list blockers before warnings`() {
        val r = run(
            CheckResult.warn("Boards", "Firmware", "Octa6", "firmware 0.15"),
            CheckResult.fail("FPP", "fppd", "FPP", "not running"),
        )
        assertEquals(listOf("FPP: not running", "Octa6: firmware 0.15"), r.topProblems())
    }

    @Test
    fun `report html escapes text and shows the verdict banner`() {
        val r = run(CheckResult.fail("Boards", "Answering", "Front <Left>", "not answering"))
        val html = ReportRenderer(PreShowProperties()).render(r)
        assertTrue(html.contains("Front &lt;Left&gt;"))
        assertFalse(html.contains("Front <Left>"))
        assertTrue(html.contains("NO-GO"))
        assertTrue(html.contains("Needs attention"))
    }

    @Test
    fun `human durations`() {
        assertEquals("5 min", humanDuration(300))
        assertEquals("2 h 5 min", humanDuration(7500))
        assertEquals("3 d 4 h", humanDuration(3 * 86_400 + 4 * 3_600 + 59))
    }
}
