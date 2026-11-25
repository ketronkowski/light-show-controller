package com.ketronkowski.xlights.jukebox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.brief
import java.time.Instant

/** What FPP is playing right now, from `/api/fppd/status`. */
data class PlayState(
    val status: String,
    val playlist: String?,
    val sequence: String?,
    val secondsPlayed: Int,
    val secondsRemaining: Int,
) {
    val playing: Boolean get() = status == "playing"
    fun on(name: String) = playing && playlist.equals(name, ignoreCase = true)
}

/** FPP and Remote Falcon, as the jukebox test needs them. */
interface JukeboxRig {
    fun state(): PlayState
    fun startPlaylist(name: String)
    fun stopNow()
    fun volume(): Int?
    fun setVolume(volume: Int)
    /** Queues [sequence] in Remote Falcon as an owner request (no GPS). */
    fun request(sequence: String)
    fun purgeQueue()
}

/** One step of the test and how it went. */
data class JukeboxStep(val name: String, val ok: Boolean, val message: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JukeboxRun(
    val startedAt: Instant,
    val finishedAt: Instant,
    val steps: List<JukeboxStep>,
    /** Set when the test didn't run at all (FPP busy, not configured, ...). */
    val skippedBecause: String? = null,
    /** Set when cleanup failed, e.g. FPP may still be playing. */
    val cleanupError: String? = null,
) {
    val ok: Boolean get() = skippedBecause == null && steps.isNotEmpty() && steps.all { it.ok }
}

/**
 * The jukebox interrupt test, step by step:
 * 1. Background plays (muted).
 * 2. A request cuts in within [PreShowProperties.JukeboxTest.cutInSeconds].
 * 3. A second request made during the first waits: the first plays to its end, then the second.
 * 4. When the queue is empty, Background resumes the song it paused, at about the same spot.
 * Cleanup always runs: stop FPP, purge the queue, restore the volume.
 */
class JukeboxTester(
    private val rig: JukeboxRig,
    private val cfg: PreShowProperties.JukeboxTest,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val steps = mutableListOf<JukeboxStep>()
    private class Stop : RuntimeException()

    private fun pass(name: String, message: String) { steps += JukeboxStep(name, true, message) }
    private fun fail(name: String, message: String): Nothing { steps += JukeboxStep(name, false, message); throw Stop() }

    /** Polls once a second until [done] or [seconds] pass; returns the last state seen and whether [done] held. */
    private fun waitFor(seconds: Int, done: (PlayState) -> Boolean): Pair<PlayState, Boolean> {
        val end = clock() + seconds * 1000L
        var s = rig.state()
        while (!done(s)) {
            if (clock() >= end) return s to false
            sleep(1000)
            s = rig.state()
        }
        return s to true
    }

    fun run(): JukeboxRun {
        val started = Instant.ofEpochMilli(clock())
        val volume = runCatching { rig.volume() }.getOrNull()
        var cleanupError: String? = null
        try {
            if (cfg.mute) {
                // Without the volume we couldn't put it back after muting: don't play at all.
                if (volume == null) fail("Mute", "couldn't read FPP's volume, so the test didn't mute it and didn't play")
                rig.setVolume(0)
            }
            rig.purgeQueue()
            test()
        } catch (_: Stop) {
        } catch (e: Exception) {
            steps += JukeboxStep("Test", false, "stopped with an error: ${e.brief()}")
        } finally {
            val errors = mutableListOf<String>()
            runCatching { rig.stopNow() }.onFailure { errors += "stop FPP: ${it.brief()}" }
            runCatching { rig.purgeQueue() }.onFailure { errors += "purge the request queue: ${it.brief()}" }
            if (cfg.mute && volume != null) runCatching { rig.setVolume(volume) }.onFailure { errors += "restore volume $volume: ${it.brief()}" }
            if (errors.isNotEmpty()) cleanupError = errors.joinToString("; ")
        }
        return JukeboxRun(started, Instant.ofEpochMilli(clock()), steps.toList(), cleanupError = cleanupError)
    }

    private fun test() {
        val bg = cfg.backgroundPlaylist
        val remote = cfg.remotePlaylist

        rig.startPlaylist(bg)
        val (playing, started) = waitFor(30) { it.on(bg) && it.secondsPlayed >= 3 }
        if (!started) fail("Background plays", "Background didn't start (FPP: ${describe(playing)})")
        pass("Background plays", "${playing.sequence} playing")

        // 1st request: must cut in. Remember where Background was when it did.
        val requestedAt = clock()
        rig.request(cfg.testSequence)
        var lastBg = playing
        val (cut, cutIn) = waitFor(cfg.cutInSeconds) { s -> if (s.on(bg)) lastBg = s; s.on(remote) }
        if (!cutIn) fail("Request cuts in", "the request didn't cut into Background within ${cfg.cutInSeconds} s " +
            "(FPP: ${describe(cut)}): is \"Interrupt Schedule\" on in the Remote Falcon plugin?")
        pass("Request cuts in", "cut in after ${"%.1f".format((clock() - requestedAt) / 1000.0)} s")

        // 2nd request while the 1st plays: the 1st must play to its end, then the 2nd.
        rig.request(cfg.testSequence)
        var prev = cut
        // When the current song started, by FPP's count; a jump forward means a new song started
        // (works even when the new one cut in only a second or two after the first).
        fun startedAt(s: PlayState) = clock() / 1000.0 - s.secondsPlayed
        var prevStart = startedAt(cut)
        var secondStarted = false
        val end = clock() + cfg.maxSongSeconds * 1000L
        while (!secondStarted) {
            if (clock() >= end) fail("Next request waits", "the first request was still playing after ${cfg.maxSongSeconds} s")
            sleep(1000)
            val s = rig.state()
            when {
                s.on(remote) && startedAt(s) > prevStart + 1.5 -> {
                    // A new song started. Only fine if the previous one had (nearly) finished.
                    if (prev.secondsRemaining > 3) fail("Next request waits",
                        "the second request cut the first one off at ${clock(prev.secondsPlayed)} with ${prev.secondsRemaining} s left")
                    secondStarted = true
                }
                s.on(remote) -> { prev = s; prevStart = minOf(prevStart, startedAt(s)) }
                s.on(bg) -> fail("Next request waits", "Background came back after the first request: the second request was lost")
                else -> { /* between songs: keep waiting */ }
            }
        }
        pass("Next request waits", "the first request played to the end, then the second started")

        // Queue empty: Background resumes the paused song, near where it stopped.
        val (back, resumed) = waitFor(cfg.maxSongSeconds + 10) { it.on(bg) }
        if (!resumed) fail("Background resumes", "Background didn't come back after the requests (FPP: ${describe(back)})")
        val sameSong = back.sequence.equals(lastBg.sequence, ignoreCase = true)
        val near = back.secondsPlayed >= lastBg.secondsPlayed - 2 && back.secondsPlayed <= lastBg.secondsPlayed + 15
        if (!sameSong || !near) fail("Background resumes",
            "Background came back with ${back.sequence} at ${clock(back.secondsPlayed)}, " +
                "not ${lastBg.sequence} at ${clock(lastBg.secondsPlayed)} where it was paused")
        pass("Background resumes", "${back.sequence} resumed at ${clock(back.secondsPlayed)} (paused at ${clock(lastBg.secondsPlayed)})")
    }

    private fun describe(s: PlayState) =
        if (s.playing) "playing ${s.sequence ?: "?"} from ${s.playlist ?: "?"}" else s.status

    private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
}
