package com.ketronkowski.xlights.preshow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FppCheckerTest {

    private val props = PreShowProperties(fpp = PreShowProperties.FppChecks(expectedVolume = 72))
    private val fpp = FppChecker(http(), props, "admin", "")

    private fun epoch(h: Int, m: Int = 0) = at(h, m).epochSecond

    @Test
    fun `a Remote playlist running across show start passes`() {
        val items = json("""[
            {"playlist":"Background","enabled":true,"startTime":${epoch(10)},"endTime":${epoch(16, 55)}},
            {"playlist":"Remote","enabled":true,"overridden":false,"startTime":${epoch(17)},"endTime":${epoch(22)}}
        ]""")
        val r = fpp.scheduleResult(items, ctx(), "FPP")
        assertEquals(Outcome.PASS, r.outcome)
        assertEquals("Remote scheduled 5:00 PM to 10:00 PM", r.message)
    }

    @Test
    fun `no show playlist at show start is a blocker, disabled entries do not count`() {
        val items = json("""[
            {"playlist":"Remote","enabled":false,"startTime":${epoch(17)},"endTime":${epoch(22)}},
            {"playlist":"Background","enabled":true,"startTime":${epoch(17)},"endTime":${epoch(22)}}
        ]""")
        val r = fpp.scheduleResult(items, ctx(), "FPP")
        assertEquals(Outcome.FAIL, r.outcome)
        assertTrue(r.message.contains("no Remote playlist scheduled at 5:00 PM"))
    }

    @Test
    fun `before the first show night a missing schedule is only a warning`() {
        val items = json("""[{"playlist":"Background","enabled":true,"startTime":${epoch(17)},"endTime":${epoch(22)}}]""")
        val dry = ctx().copy(firstShowNight = java.time.LocalDate.of(2026, 11, 27))
        val r = fpp.scheduleResult(items, dry, "FPP")
        assertEquals(Outcome.WARN, r.outcome)
        assertTrue(r.message.contains("dry run, first show night is 2026-11-27"))
        val showNight = ctx().copy(firstShowNight = java.time.LocalDate.of(2026, 11, 26))
        assertEquals(Outcome.FAIL, fpp.scheduleResult(items, showNight, "FPP").outcome)
    }

    @Test
    fun `playlist validation reports missing files, empty and absent playlists`() {
        val all = json("""[
            {"name":"Remote","valid":false,"messages":["Sequence 'Althea.fseq' not found"],"total_items":"25"},
            {"name":"Background","valid":true,"messages":[],"total_items":"0"}
        ]""")
        val r = FppChecker(http(), PreShowProperties(fpp = PreShowProperties.FppChecks(validatePlaylists = listOf("Remote", "Background", "PreShow"))), "admin", "")
            .playlistResults(all)
        assertEquals(listOf(Outcome.FAIL, Outcome.FAIL, Outcome.FAIL), r.map { it.outcome })
        assertEquals("Sequence 'Althea.fseq' not found", r[0].message)
        assertEquals("playlist Background is empty", r[1].message)
        assertEquals("playlist PreShow not found on FPP", r[2].message)
    }

    @Test
    fun `outputs must exist and be active for every board, channel counts are compared`() {
        val outputs = json("""{"channelOutputs":[{"type":"universes","enabled":1,"universes":[
            {"active":1,"address":"192.168.6.25","channelCount":900},
            {"active":0,"address":"192.168.6.26","channelCount":900},
            {"active":1,"address":"192.168.6.27","channelCount":600}
        ]}]}""")
        val c = ctx(controllers = listOf(
            board("Octa5", "192.168.6.25"), board("Octa6", "192.168.6.26"),
            board("Octa7", "192.168.6.27"), board("Octa8", "192.168.6.28"),
        ))
        val r = fpp.outputResults(outputs, c).associate { it.target to it.outcome }
        assertEquals(mapOf("Octa5" to Outcome.PASS, "Octa6" to Outcome.FAIL, "Octa7" to Outcome.WARN, "Octa8" to Outcome.FAIL), r)
    }

    @Test
    fun `disabled outputs are one blocker`() {
        val outputs = json("""{"channelOutputs":[{"type":"universes","enabled":0,"universes":[]}]}""")
        val r = fpp.outputResults(outputs, ctx(controllers = listOf(board("Octa5", "192.168.6.25"))))
        assertEquals(1, r.size)
        assertEquals(Outcome.FAIL, r[0].outcome)
    }

    @Test
    fun `status warnings cover volume, MQTT, under-voltage, temperature and restarts`() {
        val s = json("""{"fppd":"running","status_name":"idle","volume":60,"powerBad":true,
            "MQTT":{"configured":true,"connected":false},
            "scheduler":{"enabled":1},
            "sensors":[{"label":"CPU: ","value":81.2,"valueType":"Temperature"}],
            "systemUptimeTotalSeconds":3600,
            "warnings":["Audio device missing"]}""")
        val r = fpp.statusChecks(s, "FPP", ctx())
        val byName = r.associate { it.name to it.outcome }
        assertEquals(Outcome.PASS, byName["fppd"])
        assertEquals(Outcome.WARN, byName["Volume"])
        assertEquals(Outcome.WARN, byName["MQTT"])
        assertEquals(Outcome.WARN, byName["Power supply"])
        assertEquals(Outcome.WARN, byName["CPU temperature"])
        assertEquals(Outcome.WARN, byName["Restarts"])
        assertEquals(Outcome.WARN, byName["FPP warnings"])
    }

    @Test
    fun `fppd not running and scheduler off are blockers`() {
        val s = json("""{"fppd":"Not Running","status_name":"stopped","scheduler":{"enabled":0}}""")
        val r = fpp.statusChecks(s, "FPP", ctx()).associate { it.name to it.outcome }
        assertEquals(Outcome.FAIL, r["fppd"])
        assertEquals(Outcome.FAIL, r["Scheduler"])
    }

    @Test
    fun `clock drift over the limit warns`() {
        val now = at(14)
        assertEquals(Outcome.PASS, fpp.clockCheck(now.plusSeconds(2), now, "FPP").single().outcome)
        assertEquals(Outcome.WARN, fpp.clockCheck(now.minusSeconds(30), now, "FPP").single().outcome)
    }
}
