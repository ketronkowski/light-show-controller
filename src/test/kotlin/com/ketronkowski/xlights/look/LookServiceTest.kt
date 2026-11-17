package com.ketronkowski.xlights.look

import com.ketronkowski.xlights.domain.*
import com.ketronkowski.xlights.validation.ValidationService
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.dto.WledSegmentDto
import com.ketronkowski.xlights.wled.dto.WledStateResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LookServiceTest {

    private val controller = XLightsController(
        name = "Octa5", ipAddress = "192.168.6.25", protocol = "DDP", totalChannels = 615,
        models = listOf(
            XLightsModel("Arch",     "Octa5", startChannel = 1,   channelCount = 300, isNull = false),
            XLightsModel("Null-gap", "Octa5", startChannel = 301, channelCount = 15,  isNull = true),
            XLightsModel("Tree",     "Octa5", startChannel = 316, channelCount = 300, isNull = false),
        ),
    )
    private val device = WledDevice(
        name = "Octa5", ipAddress = "192.168.6.25", firmwareVersion = "0.15.0",
        totalLeds = 205, bytesPerPixel = 3, maxSegments = 32,
        segments = listOf(
            WledSegment(0, "Arch",     0,   100, on = true),
            WledSegment(1, "Null-gap", 100, 105, on = false),
            WledSegment(2, "Tree",     105, 205, on = true),
        ),
    )
    private val inSync = ControllerValidation(
        xLightsController = controller, wledDevice = device, expectedTotalLeds = 205, totalLedsMatch = true,
        segmentValidations = controller.models.mapIndexed { i, m ->
            SegmentValidation(m, device.segments[i].start, device.segments[i].stop, device.segments[i], SegmentStatus.OK)
        },
        orphanSegments = emptyList(),
    )
    private val catalog = ControllerCatalog(
        effects  = listOf("Solid", "Blink", "Chase 3", "RSVD", "Black Hole", "Gravimeter"),
        palettes = listOf("Default", "* Random Cycle", "* Color 1", "Party", "Ocean"),
        fxData   = listOf("", "!,Duty cycle;!,!;!;01", "!,Size;!,!,!;!", "", "!,!;;;2", "Rate of fall,Sensitivity;!,!;!;1v;ix=128"),
    )

    private val apiClient = mock<WledApiClient>()
    private val validationService = mock<ValidationService>()
    private val service = LookService(apiClient, validationService, "/tmp/unused")

    private val look = Look(
        on = true, bri = 180, fx = "Chase 3", sx = 10, ix = 20, c1 = 30, c2 = 40, c3 = 99,
        o1 = true, pal = "Party", col = listOf(listOf(255, 0, 0), listOf(0, 0, 300)),
    )

    @Test
    fun `look goes to non-null model segments, null segment is turned off, every segment has an id`() {
        val body = service.buildBody(inSync, catalog, look)

        assertEquals(true, body["on"])
        assertEquals(180, body["bri"])
        assertEquals(0, body["tt"])
        assertEquals(0, body["tb"])
        assertEquals(mapOf("nn" to true), body["udpn"])
        assertFalse(body.containsKey("lor"), "must never override a running show")

        @Suppress("UNCHECKED_CAST")
        val segs = body["seg"] as List<Map<String, Any?>>
        assertEquals(listOf(0, 1, 2), segs.map { it["id"] })
        assertEquals(mapOf("id" to 1, "on" to false), segs[1])
        for (s in listOf(segs[0], segs[2])) {
            assertEquals(true, s["on"])
            assertEquals(2, s["fx"])           // "Chase 3" → this controller's id
            assertEquals(3, s["pal"])          // "Party"
            assertEquals(10, s["sx"])
            assertEquals(31, s["c3"])          // clamped to WLED's 0..31
            assertEquals(true, s["o1"])
            assertEquals(listOf(listOf(255, 0, 0), listOf(0, 0, 255), listOf(0, 0, 0)), s["col"])
        }
    }

    @Test
    fun `segment with no matching model is turned off`() {
        val extra = WledSegment(3, "Leftover", 0, 10, on = true)
        val cv = inSync.copy(wledDevice = device.copy(segments = device.segments + extra))
        @Suppress("UNCHECKED_CAST")
        val segs = service.buildBody(cv, catalog, look)["seg"] as List<Map<String, Any?>>
        assertEquals(mapOf("id" to 3, "on" to false), segs[3])
    }

    @Test
    fun `power off still sets the effect and colors on the segments`() {
        val on = service.buildBody(inSync, catalog, look)
        val off = service.buildBody(inSync, catalog, look.copy(on = false))
        assertEquals(false, off["on"])
        assertEquals(on["seg"], off["seg"])
        assertEquals(mapOf("nn" to true), off["udpn"])
    }

    @Test
    fun `power switch posts only on-off to every controller, and reports failures`() {
        whenever(apiClient.postState(eq("192.168.6.26"), any())).thenThrow(RuntimeException("timeout"))
        val out = service.power(PowerRequest(listOf(LookTarget("Octa5", "192.168.6.25"), LookTarget("Octa6", "192.168.6.26")), on = false))
        verify(apiClient).postState("192.168.6.25", mapOf("on" to false, "tt" to 0, "udpn" to mapOf("nn" to true)))
        assertTrue(out[0].success)
        assertFalse(out[1].success)
        assertEquals("timeout", out[1].error)
    }

    @Test
    fun `differing looks lists controllers whose effect or settings differ from the first`() {
        fun state(fx: Int, sx: Int = 128, on: Boolean = true, bri: Int = 128) = WledStateResponse(
            on = on, bri = bri, seg = listOf(WledSegmentDto(id = 0, fx = fx, sx = sx, col = listOf(listOf(255, 0, 0)))))
        val out = service.differingLooks(listOf(
            Triple("a", state(2), catalog),
            Triple("b", state(2, on = false, bri = 10), catalog),   // same look, only power/brightness differ
            Triple("c", state(1), catalog),                         // different effect
            Triple("d", state(2, sx = 5), catalog),                 // same effect, different speed
        ))
        assertEquals(mapOf("c" to "Blink", "d" to "Chase 3"), out)
    }

    @Test
    fun `unknown effect name is an error, not a guess`() {
        val e = assertThrows(IllegalStateException::class.java) {
            service.buildBody(inSync, catalog, look.copy(fx = "Not There"))
        }
        assertTrue(e.message!!.contains("Not There"))
    }

    @Test
    fun `not ready when LED count differs, segments drift, or there are orphans`() {
        assertNull(service.notReadyReason(inSync))
        assertTrue(service.notReadyReason(inSync.copy(totalLedsMatch = false))!!.contains("LED count"))
        val drift = inSync.copy(segmentValidations = inSync.segmentValidations.mapIndexed { i, sv ->
            if (i == 0) sv.copy(status = SegmentStatus.RANGE_MISMATCH) else sv })
        assertTrue(service.notReadyReason(drift)!!.contains("Update Selected"))
        val orphan = inSync.copy(orphanSegments = listOf(WledSegment(3, "x", 0, 1, true)))
        assertNotNull(service.notReadyReason(orphan))
        assertNotNull(service.notReadyReason(inSync.copy(wledDevice = null)))
    }

    @Test
    fun `common effects drop RSVD, 2D-only and audio-reactive, Solid first then alphabetical`() {
        val other = catalog.copy(effects = listOf("Solid", "Chase 3", "Blink", "RSVD", "Black Hole", "Gravimeter"))
        val names = service.commonEffects(listOf(catalog, other)).map { it.name }
        assertEquals(listOf("Solid", "Blink", "Chase 3"), names)
    }

    @Test
    fun `common palettes keep Default, then specials, then names alphabetically`() {
        assertEquals(listOf("Default", "* Random Cycle", "* Color 1", "Ocean", "Party"),
            service.commonPalettes(listOf(catalog)))
    }

    @Test
    fun `apply rejects a controller that drifted out of sync and posts to the rest`() {
        val drifted = inSync.copy(
            xLightsController = controller.copy(name = "Octa6"),
            wledDevice = device.copy(name = "Octa6", ipAddress = "192.168.6.26"),
            totalLedsMatch = false,
        )
        whenever(validationService.validate(any(), any(), any())).thenReturn(
            ValidationReport(listOf(inSync, drifted), emptyList(), emptyList()))
        whenever(apiClient.fetchEffects("192.168.6.25")).thenReturn(catalog.effects)
        whenever(apiClient.fetchPalettes("192.168.6.25")).thenReturn(catalog.palettes)
        whenever(apiClient.fetchFxData("192.168.6.25")).thenReturn(catalog.fxData)

        val out = service.apply(ApplyLookRequest(listOf(LookTarget("Octa5"), LookTarget("Octa6")), look))

        assertTrue(out[0].success)
        assertFalse(out[1].success)
        assertTrue(out[1].error!!.contains("LED count"))
        verify(apiClient).postState(eq("192.168.6.25"), any())
        verify(apiClient, never()).postState(eq("192.168.6.26"), any())
    }

    // ── Floodlights ──────────────────────────────────────────────────────────

    private val withFloods: ControllerValidation = run {
        val ctrl = controller.copy(models = controller.models +
            XLightsModel("front-floods", "Octa5", startChannel = 616, channelCount = 16, isNull = false, bytesPerNode = 4))
        val dev = device.copy(segments = device.segments + WledSegment(3, "front-floods", 205, 209, on = true), totalLeds = 209)
        inSync.copy(
            xLightsController = ctrl, wledDevice = dev, expectedTotalLeds = 209,
            segmentValidations = ctrl.models.mapIndexed { i, m ->
                SegmentValidation(m, dev.segments[i].start, dev.segments[i].stop, dev.segments[i], SegmentStatus.OK)
            },
        )
    }

    @Test
    fun `floodlight names are recognised by the word flood`() {
        assertTrue(isFloodlight("front-floods"))
        assertTrue(isFloodlight("Garage Flood 2"))
        assertTrue(isFloodlight("FLOODLIGHT"))
        assertFalse(isFloodlight("Arch"))
        assertFalse(isFloodlight(null))
    }

    @Test
    fun `floodlights get their own look, everything else keeps the main one`() {
        val flood = Look(fx = "Solid", pal = "Default", col = listOf(listOf(255, 200, 0)), bri = 1, on = false)
        val body = service.buildBody(withFloods, catalog, look, flood)

        assertEquals(true, body["on"], "power comes from the main look")
        assertEquals(180, body["bri"], "brightness comes from the main look")
        @Suppress("UNCHECKED_CAST")
        val segs = body["seg"] as List<Map<String, Any?>>
        assertEquals(listOf(0, 1, 2, 3), segs.map { it["id"] })
        assertEquals(2, segs[0]["fx"])
        assertEquals(2, segs[2]["fx"])
        assertEquals(mapOf("id" to 1, "on" to false), segs[1])
        assertEquals(true, segs[3]["on"])
        assertEquals(0, segs[3]["fx"])          // Solid
        assertEquals(0, segs[3]["pal"])
        assertEquals(listOf(listOf(255, 200, 0), listOf(0, 0, 0), listOf(0, 0, 0)), segs[3]["col"])
    }

    @Test
    fun `without a flood look the floodlights get the main look`() {
        @Suppress("UNCHECKED_CAST")
        val segs = service.buildBody(withFloods, catalog, look)["seg"] as List<Map<String, Any?>>
        assertEquals(segs[0], segs[3] + mapOf("id" to 0))
    }

    @Test
    fun `a flood look is ignored on a controller without floodlights`() {
        val bad = Look(fx = "Not On This Controller")
        assertEquals(service.buildBody(inSync, catalog, look), service.buildBody(inSync, catalog, look, bad))
    }

    @Test
    fun `catalog reports floodlights and pre-fills from a non-flood segment`() {
        val ip = "192.168.6.25"
        whenever(apiClient.fetchEffects(ip)).thenReturn(catalog.effects)
        whenever(apiClient.fetchPalettes(ip)).thenReturn(catalog.palettes)
        whenever(apiClient.fetchFxData(ip)).thenReturn(catalog.fxData)
        whenever(apiClient.fetchState(ip)).thenReturn(WledStateResponse(mainseg = 1, seg = listOf(
            WledSegmentDto(id = 0, name = "Arch", fx = 2),
            WledSegmentDto(id = 1, name = "front-floods", fx = 0, col = listOf(listOf(255, 200, 0))),
        )))

        val cat = service.catalog(listOf(ip))

        assertEquals(mapOf(ip to listOf("front-floods")), cat.floodlights)
        assertEquals("Chase 3", cat.current!!.fx)
        assertEquals("Solid", cat.currentFlood!!.fx)
        assertEquals(listOf(255, 200, 0), cat.currentFlood!!.col[0])
    }

    @Test
    fun `catalog retries a controller once when a response is cut off`() {
        val ip = "192.168.6.43"
        whenever(apiClient.fetchEffects(ip))
            .thenThrow(RuntimeException("Error while extracting response", RuntimeException("Unexpected end-of-input")))
            .thenReturn(catalog.effects)
        whenever(apiClient.fetchPalettes(ip)).thenReturn(catalog.palettes)
        whenever(apiClient.fetchFxData(ip)).thenReturn(catalog.fxData)
        whenever(apiClient.fetchState(ip)).thenReturn(WledStateResponse(seg = listOf(WledSegmentDto(id = 0, name = "Arch"))))

        val cat = service.catalog(listOf(ip))

        assertEquals(emptyMap<String, String>(), cat.unreachable)
        assertEquals("Solid", cat.effects.first().name)
    }

    @Test
    fun `a controller that fails twice is reported with the underlying cause`() {
        val ip = "192.168.6.43"
        whenever(apiClient.fetchEffects(ip)).thenThrow(
            RuntimeException("Error while extracting response for type [java.util.List<java.lang.String>]",
                RuntimeException("Unexpected end-of-input: expected close marker for Array")))

        val cat = service.catalog(listOf(ip))

        assertEquals("Error while extracting response: Unexpected end-of-input: expected close marker for Array",
            cat.unreachable[ip])
    }
}
