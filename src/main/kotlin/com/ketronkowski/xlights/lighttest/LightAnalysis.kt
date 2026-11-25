package com.ketronkowski.xlights.lighttest

import com.ketronkowski.xlights.preshow.PreShowProperties
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Turns pictures into calibration masks and nightly ON / DIM / OFF verdicts. Pure, no I/O. */
object LightAnalysis {

    /**
     * The pixels one model brightened on calibration night, with how much, before noise is removed.
     * Kept as two plain arrays (sorted pixel index, gain): a boxed map per model ran a 154-model
     * calibration out of heap.
     */
    class Candidate(val target: ModelTarget, val pixels: IntArray, val gains: FloatArray) {
        val size get() = pixels.size
        fun gainAt(pixel: Int): Float = gains[pixels.binarySearch(pixel)]
    }

    fun candidate(t: ModelTarget, lit: Frame, base: Frame, cfg: PreShowProperties.LightTest): Candidate {
        val gain = LightMath.gain(lit, base)
        val th = cfg.maskThreshold.toFloat()
        var n = 0
        for (g in gain) if (g >= th) n++
        val px = IntArray(n)
        val gs = FloatArray(n)
        var k = 0
        for (i in gain.indices) if (gain[i] >= th) { px[k] = i; gs[k] = gain[i]; k++ }
        return Candidate(t, px, gs)
    }

    /**
     * Final calibration for one model: its bright pixels minus everything that flickered
     * between dark pictures, minus lone specks. Too few pixels left = not in the camera's view.
     */
    fun calibrate(c: Candidate, noise: BooleanArray?, width: Int, cfg: PreShowProperties.LightTest): ModelCal {
        val clean = c.pixels.filter { noise == null || !noise[it] }.toSet()
        val kept = LightMath.keepComponents(clean, width, cfg.minComponentPixels)
        val visible = kept.size >= cfg.minMaskPixels
        val flags = if (visible) BooleanArray((kept.maxOrNull() ?: 0) + 1).also { f -> kept.forEach { f[it] = true } } else BooleanArray(0)
        val ref = if (visible) kept.sumOf { c.gainAt(it).toDouble() } else 0.0
        val t = c.target
        return ModelCal(t.name, t.controller, t.port, t.firstChannel, t.lastChannel, visible, Mask.of(flags), ref, kept.size.takeIf { !visible })
    }

    /** One-step calibration without a noise map (tests, single model). */
    fun calibrate(t: ModelTarget, lit: Frame, base: Frame, cfg: PreShowProperties.LightTest): ModelCal =
        calibrate(candidate(t, lit, base, cfg), null, lit.width, cfg)

    /** How strongly a calibrated model lit tonight. */
    fun measure(cal: ModelCal, lit: Frame, base: Frame, cfg: PreShowProperties.LightTest): Signal =
        LightMath.signal(LightMath.gain(lit, base), cal.mask, cfg.maskThreshold.toFloat())

    /**
     * Scales every model against the night's typical one, so a brighter dusk or the
     * camera's exposure doesn't count as a fault, then sorts them into ON / DIM / OFF.
     * Returns the results and the typical (unscaled) level.
     */
    fun classify(cals: List<ModelCal>, signals: Map<String, Signal>, cfg: PreShowProperties.LightTest): Pair<List<ModelResult>, Double> {
        val raw = cals.filter { it.visible && it.reference > 0 && signals.containsKey(it.name) }
            .associate { it.name to (signals.getValue(it.name).sum / it.reference) }
        val typical = LightMath.median(raw.values.toList())
        val typicalArea = LightMath.median(cals.mapNotNull { c -> signals[c.name]?.takeIf { raw.containsKey(c.name) }?.coverage })
        // Floors keep a dark night from scaling "nothing lit" up into "normal".
        val levelScale = maxOf(typical, 0.3)
        val areaScale = maxOf(typicalArea, 0.3)
        val results = cals.map { c ->
            val r = raw[c.name]
            if (!c.visible || r == null) {
                ModelResult(c.name, c.controller, c.port, LightStatus.NOT_IN_VIEW, firstChannel = c.firstChannel)
            } else {
                val level = r / levelScale
                val area = signals.getValue(c.name).coverage / areaScale
                val status = when {
                    level < cfg.offRatio -> LightStatus.OFF
                    level < cfg.dimRatio || area < 0.6 -> LightStatus.DIM
                    else -> LightStatus.ON
                }
                ModelResult(c.name, c.controller, c.port, status, round2(level), round2(area), c.firstChannel)
            }
        }
        return results to round2(typical)
    }

    /** A picture of the yard with each visible model boxed: green on, yellow dim, red off. */
    fun annotate(base: BufferedImage, cals: List<ModelCal>, results: Map<String, LightStatus>?, width: Int): ByteArray {
        val img = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.drawImage(base, 0, 0, null)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = BasicStroke(2f)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
        // Problems last, so their boxes and labels sit on top.
        val order = cals.filter { it.visible }.sortedBy { c ->
            when (results?.get(c.name)) { LightStatus.OFF -> 2; LightStatus.DIM -> 1; else -> 0 }
        }
        for (c in order) {
            val b = c.mask.bounds(width)
            if (b[2] < 0) continue
            val status = results?.get(c.name)
            g.color = when (status) {
                LightStatus.OFF -> Color(234, 67, 53)
                LightStatus.DIM -> Color(251, 188, 4)
                else -> Color(52, 168, 83)
            }
            g.drawRect(b[0] - 2, b[1] - 2, b[2] - b[0] + 4, b[3] - b[1] + 4)
            if (results == null || status == LightStatus.OFF || status == LightStatus.DIM) {
                val y = if (b[1] > 14) b[1] - 4 else b[3] + 14
                g.color = Color(0, 0, 0, 170)
                val w = g.fontMetrics.stringWidth(c.name)
                g.fillRect(b[0] - 2, y - 11, w + 4, 14)
                g.color = Color.WHITE
                g.drawString(c.name, b[0], y)
            }
        }
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    /** Running brightest-of picture: every lit model shows up at once. */
    fun maxInto(composite: IntArray, rgb: IntArray) {
        for (i in composite.indices) {
            val a = composite[i]; val b = rgb[i]
            val r = maxOf((a shr 16) and 0xFF, (b shr 16) and 0xFF)
            val gg = maxOf((a shr 8) and 0xFF, (b shr 8) and 0xFF)
            val bb = maxOf(a and 0xFF, b and 0xFF)
            composite[i] = (r shl 16) or (gg shl 8) or bb
        }
    }

    fun toImage(rgb: IntArray, width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, width, height, rgb, 0, width) }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
