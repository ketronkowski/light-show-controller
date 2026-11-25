package com.ketronkowski.xlights.jukebox

import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.at
import com.ketronkowski.xlights.preshow.mapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate

class JukeboxServiceTest {
    @TempDir lateinit var dir: Path

    private val props = PreShowProperties(
        firstShowNight = LocalDate.of(2026, 11, 26),
        jukebox = PreShowProperties.JukeboxTest(pluginsUrl = "https://rf", showToken = "t"),
    )

    private fun service(rig: FakeRig, startMillis: Long): JukeboxService {
        rig.now = startMillis
        return JukeboxService(props, mapper, dir.toString(), { rig }, clock = { rig.now }, sleep = { rig.now += it })
    }

    @Test
    fun `runs at 4-43 and saves the result`() {
        val rig = FakeRig()
        val svc = service(rig, at(16, 43).toEpochMilli())
        val run = svc.test("fpp")
        assertThat(run.ok).isTrue()
        assertThat(svc.lastRun()?.steps?.size).isEqualTo(4)
    }

    @Test
    fun `a scheduled run doesn't start within 10 minutes of the show, a manual one does`() {
        val rig = FakeRig()
        val svc = service(rig, at(16, 51).toEpochMilli())
        assertThat(svc.test("fpp").skippedBecause).contains("too close to show time")
        assertThat(rig.calls).isEmpty()
        assertThat(svc.test("fpp", respectShowHours = false).ok).isTrue()
    }

    @Test
    fun `doesn't run while FPP is playing`() {
        val rig = FakeRig().apply { startPlaylist("Background"); calls.clear() }
        val svc = service(rig, at(16, 43).toEpochMilli())
        assertThat(svc.test("fpp").skippedBecause).isEqualTo("FPP is playing Background")
        assertThat(rig.calls).isEmpty()
    }
}
