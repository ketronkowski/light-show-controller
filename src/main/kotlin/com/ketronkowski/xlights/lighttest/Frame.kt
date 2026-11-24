package com.ketronkowski.xlights.lighttest

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * One camera frame, shrunk to analysis size: brightness per pixel (0-255) and,
 * when kept, packed RGB for pictures in the report.
 */
class Frame(val width: Int, val height: Int, val lum: FloatArray, val rgb: IntArray? = null) {
    init {
        require(lum.size == width * height) { "luminance size ${lum.size} != $width x $height" }
    }

    companion object {
        /**
         * Decodes a JPEG and box-averages it down to [targetWidth] (whole-number factor,
         * so 3840 -> 960 averages 4x4 blocks). Averaging keeps thin pixel strings visible.
         */
        fun decode(bytes: ByteArray, targetWidth: Int, keepRgb: Boolean = false): Frame {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("camera snapshot is not a readable image")
            return fromImage(img, targetWidth, keepRgb)
        }

        fun fromImage(img: BufferedImage, targetWidth: Int, keepRgb: Boolean = false): Frame {
            val f = (img.width / targetWidth.coerceAtLeast(1)).coerceAtLeast(1)
            val w = img.width / f
            val h = img.height / f
            val src = img.getRGB(0, 0, w * f, h * f, null, 0, w * f)
            val lum = FloatArray(w * h)
            val rgb = if (keepRgb) IntArray(w * h) else null
            val n = (f * f).toFloat()
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0 until f) {
                    val row = (y * f + dy) * w * f + x * f
                    for (dx in 0 until f) {
                        val p = src[row + dx]
                        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                    }
                }
                val i = y * w + x
                lum[i] = (0.299f * r + 0.587f * g + 0.114f * b) / n
                if (rgb != null) rgb[i] = ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
            return Frame(w, h, lum, rgb)
        }

        /** Per-pixel minimum of two frames of the same size: the darker of two baselines. */
        fun min(a: Frame, b: Frame): Frame {
            require(a.width == b.width && a.height == b.height) { "frames differ in size" }
            return Frame(a.width, a.height, FloatArray(a.lum.size) { minOf(a.lum[it], b.lum[it]) })
        }
    }
}

/** Pixel indices of a model in the analysis frame, stored as runs `[start, length, start, length, ...]`. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class Mask(val runs: List<Int>) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val size: Int get() = runs.chunked(2).sumOf { it[1] }

    fun forEach(action: (Int) -> Unit) {
        var i = 0
        while (i + 1 < runs.size) {
            val start = runs[i]; val len = runs[i + 1]
            for (p in start until start + len) action(p)
            i += 2
        }
    }

    /** Bounding box (x0, y0, x1, y1), inclusive. */
    fun bounds(width: Int): IntArray {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        forEach { p ->
            val x = p % width; val y = p / width
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    companion object {
        fun of(flags: BooleanArray): Mask {
            val runs = mutableListOf<Int>()
            var i = 0
            while (i < flags.size) {
                if (!flags[i]) { i++; continue }
                val start = i
                while (i < flags.size && flags[i]) i++
                runs += start; runs += i - start
            }
            return Mask(runs)
        }
    }
}

/** How strongly a model showed up in a frame, compared with the baseline. */
data class Signal(val sum: Double, val coverage: Double)

/** Pure image math for the light test, kept apart from cameras and FPP so it can be tested. */
object LightMath {

    /** Brightness gain of [lit] over [base], smoothed over 3x3 so a single noisy pixel doesn't count. */
    fun gain(lit: Frame, base: Frame): FloatArray {
        require(lit.width == base.width && lit.height == base.height) { "frames differ in size" }
        val w = lit.width; val h = lit.height
        val d = FloatArray(w * h) { maxOf(0f, lit.lum[it] - base.lum[it]) }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                s += d[yy * w + xx]; n++
            }
            out[y * w + x] = s / n
        }
        return out
    }

    /** The pixels that got at least [threshold] brighter: where the model is in the picture. */
    fun mask(gain: FloatArray, threshold: Float): Mask = Mask.of(BooleanArray(gain.size) { gain[it] >= threshold })

    /** Total gain inside [mask], and the share of its pixels that reached half the threshold. */
    fun signal(gain: FloatArray, mask: Mask, threshold: Float): Signal {
        var sum = 0.0; var hit = 0; var n = 0
        mask.forEach { p ->
            val g = gain[p]
            sum += g; n++
            if (g >= threshold / 2) hit++
        }
        return Signal(sum, if (n == 0) 0.0 else hit.toDouble() / n)
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
