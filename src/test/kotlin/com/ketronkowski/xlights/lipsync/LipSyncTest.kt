package com.ketronkowski.xlights.lipsync

import com.ketronkowski.xlights.preshow.Outcome
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.at
import com.ketronkowski.xlights.preshow.ctx
import com.ketronkowski.xlights.preshow.mapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LipSyncTest {
    @TempDir lateinit var dir: Path
    private val props = PreShowProperties()
    private val cfg = props.lipSync

    @Test
    fun `within 100 ms passes, beyond warns, few clicks warn`() {
        val ok = LipSyncChecker.result(LipSyncResult(offsetMs = 53.0, spreadMs = 12.0, pairs = 8), cfg)
        assertThat(ok.outcome).isEqualTo(Outcome.PASS)
        assertThat(ok.message).isEqualTo("picture 53 ms behind the sound (8 clicks, spread 12 ms)")
        assertThat(LipSyncChecker.result(LipSyncResult(offsetMs = -140.0, pairs = 8), cfg).outcome).isEqualTo(Outcome.WARN)
        assertThat(LipSyncChecker.result(LipSyncResult(offsetMs = 10.0, pairs = 2), cfg).message).startsWith("only 2 clicks")
        assertThat(LipSyncChecker.result(LipSyncResult(offsetMs = 10.0, pairs = 8, feederRestarted = true), cfg).message).contains("feeder restarted")
    }

    @Test
    fun `accepts clicksync json as is`() {
        val svc = LipSyncService(mapper, dir.toString())
        val r = mapper.readValue("""{"ok":true,"offset_ms":53,"pairs":7,"p10_ms":53,"p90_ms":55,"clicks":8,"flashes":7}""", LipSyncResult::class.java)
        val saved = svc.save(r, now = at(16, 48))
        assertThat(saved.offsetMs).isEqualTo(53.0)
        assertThat(saved.spreadMs).isEqualTo(2.0)
        assertThat(saved.measuredAt).isEqualTo(at(16, 48))
    }

    @Test
    fun `the checker reports a fresh result, skips a stale one, and is silent before the first`() {
        val svc = LipSyncService(mapper, dir.toString())
        val checker = LipSyncChecker(svc, props)
        assertThat(checker.check(ctx(at(16, 50)))).isEmpty()
        svc.save(LipSyncResult(offsetMs = 40.0, pairs = 8, measuredAt = at(16, 48)), now = at(16, 49))
        val fresh = checker.check(ctx(at(16, 50))).single()
        assertThat(fresh.outcome).isEqualTo(Outcome.PASS)
        assertThat(fresh.message).contains("measured 4:48 PM")
        assertThat(checker.check(ctx(at(20, 0))).single().outcome).isEqualTo(Outcome.SKIP)
    }
}
