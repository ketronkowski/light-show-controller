package com.ketronkowski.xlights.jukebox

import com.ketronkowski.xlights.preshow.Outcome
import com.ketronkowski.xlights.preshow.PreShowProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A pretend FPP + Remote Falcon plugin on a fake clock. [mode] picks how the plugin behaves:
 * "good" is Interrupt Schedule on; the others are the failures the test must catch.
 */
class FakeRig(private val mode: String = "good", private val requestSeconds: Int = 20) : JukeboxRig {
    var now = 0L
    private var playlist: String? = null
    private var sequence: String? = null
    private var songStart = 0L
    private var songLength = 0
    private var pausedAt = 0
    private var queue = 0
    private var requestedAt = 0L
    var vol = 70
    val calls = mutableListOf<String>()
    var failStop = false

    private fun play(pl: String, seq: String, length: Int, from: Int = 0) {
        playlist = pl; sequence = seq; songLength = length; songStart = now - from * 1000L
    }
    private fun played() = ((now - songStart) / 1000).toInt()

    private fun advance() {
        if (playlist == "Background" && queue > 0 && mode != "no-interrupt" && now - requestedAt >= 2000) {
            pausedAt = played(); queue--; play("Remote", "Althea Test", requestSeconds)
        }
        if (playlist == "Remote") {
            if (mode == "second-cuts" && queue > 0 && now - requestedAt >= 2000) { queue--; play("Remote", "Althea Test", requestSeconds); return }
            if (played() >= songLength) {
                val endedAt = songStart + songLength * 1000L
                if (queue > 0 && mode != "lose-second") { queue--; play("Remote", "Althea Test", requestSeconds); songStart = endedAt }
                else {
                    val resume = if (mode == "wrong-resume") 0 else pausedAt + 1
                    play("Background", "Song A", 200, resume); songStart = endedAt - resume * 1000L
                    if (mode == "lose-second") queue = 0
                }
            }
        }
    }

    override fun state(): PlayState {
        advance()
        return if (playlist == null) PlayState("idle", null, null, 0, 0)
        else PlayState("playing", playlist, sequence, played(), maxOf(0, songLength - played()))
    }
    override fun startPlaylist(name: String) { calls += "start $name"; play(name, "Song A", 200) }
    override fun stopNow() { calls += "stop"; if (failStop) error("FPP down"); playlist = null }
    override fun volume() = vol
    override fun setVolume(volume: Int) { calls += "volume $volume"; vol = volume }
    override fun request(sequence: String) { calls += "request"; queue++; requestedAt = now }
    override fun purgeQueue() { calls += "purge"; queue = 0 }
}

class JukeboxTesterTest {
    private val cfg = PreShowProperties.JukeboxTest(pluginsUrl = "https://rf", showToken = "t")

    private fun run(rig: FakeRig) = JukeboxTester(rig, cfg, clock = { rig.now }, sleep = { rig.now += it }).run()

    @Test
    fun `passes when a request cuts in, the next waits, and Background resumes`() {
        val rig = FakeRig()
        val r = run(rig)
        assertThat(r.steps.map { it.name to it.ok }).containsExactly(
            "Background plays" to true, "Request cuts in" to true, "Next request waits" to true, "Background resumes" to true)
        assertThat(r.ok).isTrue()
        assertThat(r.cleanupError).isNull()
        assertThat(rig.calls.first()).isEqualTo("volume 0")
        assertThat(rig.calls.takeLast(3)).containsExactly("stop", "purge", "volume 70")
        assertThat(rig.vol).isEqualTo(70)
    }

    @Test
    fun `fails when the request doesn't cut in`() {
        val rig = FakeRig("no-interrupt")
        val r = run(rig)
        assertThat(r.ok).isFalse()
        assertThat(r.steps.last().name).isEqualTo("Request cuts in")
        assertThat(r.steps.last().message).contains("Interrupt Schedule")
        assertThat(rig.calls).contains("stop").endsWith("volume 70")
    }

    @Test
    fun `fails when the second request cuts the first off`() {
        val r = run(FakeRig("second-cuts"))
        assertThat(r.steps.last().name).isEqualTo("Next request waits")
        assertThat(r.steps.last().ok).isFalse()
        assertThat(r.steps.last().message).contains("cut the first one off")
    }

    @Test
    fun `fails when the second request is lost`() {
        val r = run(FakeRig("lose-second"))
        assertThat(r.steps.last().name).isEqualTo("Next request waits")
        assertThat(r.steps.last().message).contains("lost")
    }

    @Test
    fun `fails when Background comes back at the wrong spot`() {
        val r = run(FakeRig("wrong-resume"))
        assertThat(r.steps.last().name).isEqualTo("Background resumes")
        assertThat(r.steps.last().ok).isFalse()
    }

    @Test
    fun `reports cleanup that failed`() {
        val rig = FakeRig().apply { failStop = true }
        val r = run(rig)
        assertThat(r.cleanupError).contains("stop FPP")
        assertThat(rig.vol).isEqualTo(70)
        assertThat(JukeboxChecker.results(r, null).last().outcome).isEqualTo(Outcome.FAIL)
    }

    @Test
    fun `step failures are warnings, a skip is one warning`() {
        val r = run(FakeRig("no-interrupt"))
        val results = JukeboxChecker.results(r, "from the 4:43 PM jukebox test")
        assertThat(results.map { it.outcome }).containsExactly(Outcome.PASS, Outcome.WARN)
        assertThat(results.last().message).endsWith("(from the 4:43 PM jukebox test)")
        val skipped = JukeboxChecker.results(JukeboxRun(java.time.Instant.EPOCH, java.time.Instant.EPOCH, emptyList(), skippedBecause = "FPP is playing"), null)
        assertThat(skipped.single().outcome).isEqualTo(Outcome.WARN)
    }

    @Test
    fun `parses FPP status`() {
        val s = FppJukeboxRig.parseState(com.ketronkowski.xlights.preshow.json(
            """{"status_name":"playing","current_playlist":{"playlist":"Background"},"current_sequence":"Song A.fseq","seconds_played":"12","seconds_remaining":"30"}"""))
        assertThat(s).isEqualTo(PlayState("playing", "Background", "Song A", 12, 30))
        assertThat(FppJukeboxRig.parseState(com.ketronkowski.xlights.preshow.json("""{"status_name":"idle","current_playlist":{"playlist":""},"current_sequence":""}""")))
            .isEqualTo(PlayState("idle", null, null, 0, 0))
    }
}
