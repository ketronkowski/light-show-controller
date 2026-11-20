package com.ketronkowski.xlights.preshow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.nio.file.Path

class PowerCheckerTest {

    @TempDir lateinit var dir: Path

    @Test
    fun `standby current well above its usual level warns once there is history`() {
        val checker = PowerChecker(mock(), PreShowProperties(), PreShowStore(mapper, dir.toString()))
        repeat(5) { assertEquals(Outcome.PASS, checker.currentResult(1.0, ctx()).outcome) }
        assertEquals(Outcome.PASS, checker.currentResult(1.3, ctx()).outcome)
        assertEquals(Outcome.WARN, checker.currentResult(2.5, ctx()).outcome)
    }

    @Test
    fun `fixed ceiling warns and readings during the show are not judged`() {
        val props = PreShowProperties(ha = PreShowProperties.HomeAssistant(maxStandbyAmps = 2.0))
        val checker = PowerChecker(mock(), props, PreShowStore(mapper, dir.toString()))
        assertEquals(Outcome.WARN, checker.currentResult(2.4, ctx()).outcome)
        assertEquals(Outcome.PASS, checker.currentResult(11.0, ctx(now = at(19))).outcome)
    }
}
