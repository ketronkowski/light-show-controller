package com.ketronkowski.xlights.lighttest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.ketronkowski.xlights.preshow.JsonHttp
import com.ketronkowski.xlights.preshow.PreShowProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.web.client.RestClient
import java.nio.file.Path

class LightTestServiceBusyTest {

    /** FPP that fails every command, the way a reply it can't read used to. */
    private class FailingLighter : Lighter {
        override fun idle(host: String) = true
        override fun controllerStarts(host: String): Map<String, Int> = throw IllegalStateException("Unrecognized token 'Stopped'")
        override fun light(host: String, first: Int, last: Int, color: String) = throw IllegalStateException("nope")
        override fun stop(host: String) = throw IllegalStateException("Unrecognized token 'Stopped'")
    }

    @Test
    fun `a failed calibration doesn't leave the light test busy`(@TempDir dir: Path) {
        val props = PreShowProperties(
            fpp = PreShowProperties.FppChecks(host = "fpp"),
            lights = PreShowProperties.LightTest(protectUrl = "x", protectApiKey = "k", cameraId = "c"),
        )
        val service = LightTestService(FailingLighter(), Camera { ByteArray(0) },
            mock { on { parse(any()) } doReturn emptyList() }, mock(), mock(), props, ObjectMapper().registerKotlinModule(), dir.toString())
        service.startCalibration()
        val deadline = System.currentTimeMillis() + 5000
        while (service.status().busy && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val status = service.status()
        assertFalse(status.busy, "still busy: ${status.message}")
        assertTrue(status.message!!.startsWith("calibration failed"), status.message)
        // And it can be started again.
        service.startCalibration()
    }

    @Test
    fun `FPP command replies in plain text don't fail`() {
        val http = JsonHttp(RestClient.create(), ObjectMapper())
        assertEquals("Stopped", http.parseLenient("Stopped").asText())
        assertEquals("Playlist Starting", http.parseLenient("Playlist Starting\n").asText())
        assertEquals(1, http.parseLenient("{\"a\":1}").path("a").asInt())
        assertTrue(http.parseLenient("").isObject)
    }
}

class LightCalibrationMergeTest {
    private fun cal(name: String, visible: Boolean) = ModelCal(name, "Octa5", 3, 1, 3, visible)

    @Test
    fun `recalibrating missing models keeps the others and the layout order`() {
        val previous = listOf(cal("Eaves - Garage", false), cal("Vertical - UFR", true), cal("Eaves - UFR", false), cal("Gone", true))
        val fresh = listOf(cal("Eaves - Garage", true), cal("Eaves - UFR", true), cal("New", false))
        val merged = LightTestService.merge(listOf("Eaves - Garage", "Vertical - UFR", "Eaves - UFR", "New"), fresh, previous)
        assertEquals(listOf("Eaves - Garage", "Vertical - UFR", "Eaves - UFR", "New"), merged.map { it.name })
        assertEquals(listOf(true, true, true, false), merged.map { it.visible })
    }
}
