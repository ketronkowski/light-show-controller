package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShowParsingTest {
    private val m = ObjectMapper()
    private fun json(s: String) = m.readTree(s)

    @Test
    fun `playing status reads song, position, time and schedule`() {
        val s = json("""
            {"status_name":"playing","current_playlist":{"playlist":"Remote","index":"3","count":"12"},
             "current_sequence":"Wizards in Winter.fseq","current_song":"Wizards in Winter.mp3",
             "seconds_played":"62","seconds_remaining":"121","volume":72,
             "scheduler":{"enabled":1,"currentPlaylist":{"playlistName":"Remote","scheduledEndTimeStr":"10:00 PM"},
                          "nextPlaylist":{"playlistName":"Background","scheduledStartTimeStr":"Tomorrow @ 4:30 PM"}},
             "sensors":[{"label":"CPU: ","value":51.2,"valueType":"Temperature"}],"powerBad":false}
        """)
        val info = json("""{"Version":"9.3","Utilization":{"Disk":{"Media":{"Free":40000000000,"Total":64000000000}}}}""")
        val f = parseFpp("192.168.6.10", s, info, 72)
        assertEquals("playing", f.status)
        assertEquals("Wizards in Winter", f.song)
        assertEquals(3, f.index); assertEquals(12, f.count)
        assertEquals(62, f.secondsPlayed); assertEquals(121, f.secondsRemaining)
        assertEquals("Remote", f.scheduledPlaylist); assertEquals("10:00 PM", f.scheduledEnd)
        assertEquals("Background", f.nextPlaylist)
        assertEquals(51.2, f.cpuTempC)
        assertEquals("9.3", f.version)
        assertEquals(62.5, f.mediaFreePercent!!, 0.01)
        assertEquals(true, f.schedulerEnabled)
    }

    @Test
    fun `idle status has no playlist position`() {
        val f = parseFpp("fpp", json("""{"status_name":"idle","current_playlist":{"playlist":"","index":"0","count":"0"},"current_sequence":"","volume":"70"}"""), null, null)
        assertEquals("idle", f.status)
        assertNull(f.playlist); assertNull(f.index); assertNull(f.song)
        assertEquals(70, f.volume)
        assertNull(f.schedulerEnabled)
    }

    @Test
    fun `playlist entries are titled by media, then sequence`() {
        val pl = FppShowClient.parsePlaylist("Remote", json("""
            {"name":"Remote","mainPlaylist":[
              {"type":"both","sequenceName":"Carol.fseq","mediaName":"Carol of the Bells.mp3","duration":185.4},
              {"type":"sequence","sequenceName":"Intro.fseq"},
              {"type":"pause","duration":5},
              {"type":"playlist","name":"Background"}]}
        """))
        assertEquals(listOf("Carol of the Bells", "Intro", "Pause 5 s", "Playlist Background"), pl.items.map { it.title })
        assertEquals(185, pl.items[0].seconds)
        assertEquals(listOf(0, 1, 2, 3), pl.items.map { it.position })
    }

    @Test
    fun `board reads live mode and estimated current`() {
        val b = parseBoard("Octa5", "192.168.6.25", "Octa5-8", json("""
            {"state":{"on":true,"bri":128},"info":{"live":true,"leds":{"count":800,"pwr":4210,"maxpwr":15000}}}
        """))
        assertTrue(b.reachable); assertTrue(b.live)
        assertEquals(4210, b.milliamps); assertEquals(15000, b.maxMilliamps)
        assertEquals("Octa5-8", b.plug)
        val off = parseBoard("X", "1.2.3.4", null, json("""{"state":{"on":false},"info":{"leds":{"pwr":0,"maxpwr":0}}}"""))
        assertFalse(off.live); assertNull(off.maxMilliamps); assertEquals(0, off.milliamps)
    }
}
