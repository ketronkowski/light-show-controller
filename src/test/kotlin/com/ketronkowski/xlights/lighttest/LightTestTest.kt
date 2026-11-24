package com.ketronkowski.xlights.lighttest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.ketronkowski.xlights.preshow.Outcome
import com.ketronkowski.xlights.preshow.PreShowProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.time.Instant

class LightTestTest {
    private val cfg = PreShowProperties.LightTest(protectUrl = "x", protectApiKey = "k", cameraId = "c")
    private val w = 40
    private val h = 20

    /** A dark frame with a bright horizontal bar at row [y] from x0 to x1 (exclusive), at [level]. */
    private fun frame(vararg bars: IntArray, level: Float = 200f, noise: Float = 5f): Frame {
        val lum = FloatArray(w * h) { noise }
        for (b in bars) for (x in b[1] until b[2]) lum[b[0] * w + x] = level
        return Frame(w, h, lum)
    }

    private fun target(name: String, port: Int = 1) = ModelTarget(name, "Octa5", 1, 3, port)

    @Test
    fun `calibration finds where a model is and skips one the camera can't see`() {
        val dark = frame()
        val cal = LightAnalysis.calibrate(target("Eaves"), frame(intArrayOf(5, 4, 30)), dark, cfg)
        assertTrue(cal.visible)
        val b = cal.mask.bounds(w)
        assertTrue(b[0] in 2..4 && b[2] in 29..31 && b[1] in 3..5 && b[3] in 5..7)
        assertFalse(LightAnalysis.calibrate(target("Hidden"), frame(), dark, cfg).visible)
    }

    @Test
    fun `a spot that flickers between dark pictures never counts as a model`() {
        val darkA = frame()
        val darkB = frame(intArrayOf(15, 0, 12)) // a neighbor's light came on between darks
        val noise = BooleanArray(w * h)
        LightMath.noise(darkA, darkB, 18f, noise)
        // A null model lights nothing, but the flicker is in its picture.
        val lit = frame(intArrayOf(15, 0, 12))
        val c = LightAnalysis.candidate(target("Null"), lit, darkA, cfg)
        assertTrue(c.size > 30)
        assertFalse(LightAnalysis.calibrate(c, noise, w, cfg).visible)
        // With the brighter of the two darks as the baseline it doesn't show up at all.
        assertTrue(LightAnalysis.candidate(target("Null"), lit, Frame.max(darkA, darkB), cfg).size == 0)
    }

    @Test
    fun `nightly grading marks on, half-out and dead models, scaled to the night`() {
        val dark = frame()
        val names = listOf("A", "B", "C", "D", "Half", "Dead")
        val bars = names.mapIndexed { i, _ -> intArrayOf(2 + i * 3, 2, 34) }
        val cals = names.mapIndexed { i, n -> LightAnalysis.calibrate(target(n), frame(bars[i]), dark, cfg) }
        // Tonight is a dimmer dusk: everything comes in at 60% of calibration.
        val signals = names.mapIndexed { i, n ->
            val bar = when (n) {
                "Half" -> intArrayOf(bars[i][0], 2, 18)
                "Dead" -> intArrayOf(0, 0, 0)
                else -> bars[i]
            }
            n to LightAnalysis.measure(cals[i], frame(bar, level = 120f), dark, cfg)
        }.toMap()
        val (results, typical) = LightAnalysis.classify(cals, signals, cfg)
        val by = results.associate { it.name to it.status }
        assertEquals(LightStatus.ON, by["A"])
        assertEquals(LightStatus.ON, by["D"])
        assertEquals(LightStatus.DIM, by["Half"])
        assertEquals(LightStatus.OFF, by["Dead"])
        assertTrue(typical in 0.4..0.8, "typical $typical")
    }

    @Test
    fun `report rolls a dead port up into one line and keeps single models separate`() {
        val run = LightRun("r", Instant.EPOCH, Instant.EPOCH, listOf(
            ModelResult("W1", "Octa6", 2, LightStatus.OFF, 0.0, 0.0),
            ModelResult("W2", "Octa6", 2, LightStatus.OFF, 0.0, 0.0),
            ModelResult("Star", "Octa6", 1, LightStatus.ON, 1.0, 1.0),
            ModelResult("Stake", "Octa7", 3, LightStatus.OFF, 0.0, 0.0),
            ModelResult("Bush", "Octa8", 1, LightStatus.DIM, 0.4, 0.5),
            ModelResult("Garage", "Octa5", 1, LightStatus.ON, 1.0, 1.0),
            ModelResult("Mailbox", "Mailbox", 1, LightStatus.NOT_IN_VIEW),
        ))
        val r = LightTestChecker.results(run, null)
        assertEquals("2 of 6 models lit; 1 not in the camera's view", r[0].message)
        assertTrue(r.any { it.outcome == Outcome.FAIL && it.target == "Octa6 port 2" })
        assertTrue(r.any { it.outcome == Outcome.FAIL && it.target == "Stake" && it.message.contains("Octa7 port 3") })
        assertTrue(r.any { it.outcome == Outcome.WARN && it.target == "Bush" })
        assertFalse(r.any { it.target == "W1" })
    }

    @Test
    fun `everything dark is one blocker, not a row per model`() {
        val run = LightRun("r", Instant.EPOCH, Instant.EPOCH, (1..10).map { ModelResult("M$it", "Octa${it % 3}", 1, LightStatus.OFF, 0.0, 0.0) })
        val r = LightTestChecker.results(run, null)
        assertEquals(1, r.size)
        assertEquals(Outcome.FAIL, r[0].outcome)
        assertTrue(r[0].message.contains("camera moved"))
    }

    @Test
    fun `FPP outputs give each board's first channel`() {
        val outputs = ObjectMapper().readTree("""{"channelOutputs":[{"enabled":1,"universes":[
            {"address":"192.168.6.25","startChannel":1,"active":1},
            {"address":"192.168.6.25","startChannel":511,"active":1},
            {"address":"192.168.6.26","startChannel":2776,"active":1},
            {"address":"192.168.6.27","startChannel":9000,"active":0}]}]}""")
        assertEquals(mapOf("192.168.6.25" to 1, "192.168.6.26" to 2776), FppTester.starts(outputs))
    }

    @Test
    fun `masks survive a round trip through JSON and box-averaging keeps thin strings`() {
        val m = Mask.of(BooleanArray(20) { it in 3..5 || it == 9 })
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        assertEquals(m, mapper.readValue(mapper.writeValueAsString(m), Mask::class.java))
        assertEquals(4, m.size)

        val img = BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 80) img.setRGB(x, 10, 0xFFFFFF)
        val f = Frame.fromImage(img, 20)
        assertEquals(20, f.width)
        assertTrue(f.lum[2 * 20 + 5] > 50f)
    }
}

class LightTestJsonTest {
    @Test
    fun `calibration and runs read back from disk`() {
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val cal = Calibration(Instant.EPOCH, 10, 5, "#808080", listOf(ModelCal("A", "Octa5", 1, 1, 3, true, Mask(listOf(2, 3)), 9.0)))
        assertEquals(cal, mapper.readValue(mapper.writeValueAsString(cal), Calibration::class.java))
        val run = LightRun("r", Instant.EPOCH, Instant.EPOCH, listOf(ModelResult("A", "Octa5", 1, LightStatus.ON, 1.0, 1.0)))
        assertEquals(run, mapper.readValue(mapper.writeValueAsString(run), LightRun::class.java))
    }
}
