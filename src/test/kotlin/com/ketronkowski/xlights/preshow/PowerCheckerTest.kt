package com.ketronkowski.xlights.preshow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.nio.file.Path

class PowerCheckerTest {

    @TempDir lateinit var dir: Path

    private val allOn = BoardPower(on = 15, off = 0, unreachable = 0)
    private val allOff = BoardPower(on = 0, off = 15, unreachable = 0)

    private fun boards(p: BoardPower): ShowBoards = mock { on { power(any()) } doReturn p }
    private fun store() = PreShowStore(mapper, dir.toString())

    @Test
    fun `standby current well above its usual level warns once there is history`() {
        val checker = PowerChecker(mock(), PreShowProperties(), store(), boards(allOff))
        repeat(5) { assertEquals(Outcome.PASS, checker.currentResult(1.0, ctx()).outcome) }
        assertEquals(Outcome.PASS, checker.currentResult(1.3, ctx()).outcome)
        assertEquals(Outcome.WARN, checker.currentResult(2.5, ctx()).outcome)
    }

    @Test
    fun `boards on and boards off keep separate baselines`() {
        val st = store()
        val off = PowerChecker(mock(), PreShowProperties(), st, boards(allOff))
        val on = PowerChecker(mock(), PreShowProperties(), st, boards(allOn))
        repeat(5) { off.currentResult(0.8, ctx()); on.currentResult(4.4, ctx(now = at(16, 45))) }
        assertEquals(Outcome.PASS, on.currentResult(4.5, ctx(now = at(16, 49))).outcome)
        assertEquals(Outcome.PASS, off.currentResult(0.9, ctx()).outcome)
        assertEquals(Outcome.WARN, off.currentResult(4.4, ctx()).outcome)
        assertEquals(Outcome.WARN, on.currentResult(7.5, ctx(now = at(16, 49))).outcome)
    }

    @Test
    fun `mixed board state is reported but not judged or recorded`() {
        val st = store()
        val mixed = PowerChecker(mock(), PreShowProperties(), st, boards(BoardPower(14, 1, 0, listOf("Mailbox"))))
        val r = mixed.currentResult(9.0, ctx())
        assertEquals(Outcome.PASS, r.outcome)
        assertTrue(r.message.contains("Mailbox"), r.message)
        assertTrue(st.state { it.size() == 0 || it.path("standbyAmpsOn").size() == 0 })
    }

    @Test
    fun `manual runs are not added to the history`() {
        val st = store()
        val checker = PowerChecker(mock(), PreShowProperties(), st, boards(allOn))
        repeat(3) { checker.currentResult(12.0, ctx().copy(manual = true)) }
        assertEquals(0, st.state { it.path("standbyAmpsOn").size() })
    }

    @Test
    fun `old mixed history is split into on and off and outliers dropped`() {
        val st = store()
        val legacy = listOf(1.225, 1.396, 0.407, 1.526, 6.747, 0.411, 7.669, 0.686, 12.023, 4.459, 4.511, 0.849, 4.397, 1.792, 0.875, 4.485, 4.481)
        st.state { s -> s.putArray("standbyAmps").also { a -> legacy.forEach { a.add(it) } } }
        val checker = PowerChecker(mock(), PreShowProperties(), st, boards(allOn))
        assertEquals(Outcome.PASS, checker.currentResult(4.5, ctx(now = at(16, 49))).outcome)
        st.state { s ->
            assertEquals(null, s.get("standbyAmps"))
            assertEquals(listOf(4.459, 4.511, 4.397, 4.485, 4.481, 4.5), s.path("standbyAmpsOn").map { it.asDouble() })
            assertEquals(listOf(0.407, 0.411, 0.686, 0.849, 0.875), s.path("standbyAmpsOff").map { it.asDouble() })
        }
    }

    @Test
    fun `fixed ceiling warns and readings during the show are not judged`() {
        val props = PreShowProperties(ha = PreShowProperties.HomeAssistant(maxStandbyAmps = 2.0))
        val checker = PowerChecker(mock(), props, store(), boards(allOff))
        assertEquals(Outcome.WARN, checker.currentResult(2.4, ctx()).outcome)
        assertEquals(Outcome.PASS, checker.currentResult(11.0, ctx(now = at(19))).outcome)
    }
}
