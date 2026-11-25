package com.ketronkowski.xlights.lighttest

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ModelPhotosTest {
    private fun frame() = Frame(4, 2, FloatArray(8), IntArray(8) { 0x808080 })

    @Test
    fun `a finished run replaces the shown pictures, a discarded one leaves them`(@TempDir dir: Path) {
        val photos = ModelPhotos(dir)
        var s = photos.begin("test")
        photos.save(s, "Eaves / Garage", frame())
        photos.commit("test")
        assertNotNull(photos.get("test", "Eaves / Garage"))

        s = photos.begin("test")
        photos.save(s, "Mega Tree 1", frame())
        photos.discard("test")
        assertTrue(photos.has("test", "Eaves / Garage"))
        assertFalse(photos.has("test", "Mega Tree 1"))

        s = photos.begin("test")
        photos.save(s, "Mega Tree 1", frame())
        photos.commit("test")
        assertFalse(photos.has("test", "Eaves / Garage"), "a full run drops the old set")
        assertTrue(photos.has("test", "Mega Tree 1"))
    }

    @Test
    fun `recalibrating missing models keeps the other pictures`(@TempDir dir: Path) {
        val photos = ModelPhotos(dir)
        var s = photos.begin("calibration")
        photos.save(s, "A", frame()); photos.save(s, "B", frame())
        photos.commit("calibration")
        s = photos.begin("calibration")
        photos.save(s, "B", frame()); photos.save(s, "C", frame())
        photos.commit("calibration", merge = true)
        for (m in listOf("A", "B", "C")) assertTrue(photos.has("calibration", m), m)
        assertNull(photos.get("test", "A"))
    }
}
