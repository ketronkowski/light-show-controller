package com.ketronkowski.xlights.wled

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WledBootPresetSyncTest {

    private val mapper = ObjectMapper()
    private val ip = "192.168.6.25"

    // Board state after the app split output 1 into two named segments.
    private val state = """{"on":true,"bri":200,"ps":-1,"pl":-1,"lor":0,"mainseg":0,"seg":[
        {"id":0,"n":"Garage Eaves","start":0,"stop":198,"grp":1,"spc":0,"of":0,"on":true,"rev":true,"fx":9,"col":[[255,0,0],[0,0,0],[0,0,0]]},
        {"id":1,"n":"Porch Eaves","start":198,"stop":565,"grp":1,"spc":0,"of":0,"on":true,"rev":false,"fx":9,"col":[[255,0,0],[0,0,0],[0,0,0]]}
    ]}"""

    // Preset 1 "Black" as saved on Nov 19: one segment per output (3 outputs).
    private val presetsBefore = """{"0":{},"1":{"n":"Black","on":true,"bri":128,"seg":[
        {"id":0,"start":0,"stop":198,"on":true,"rev":false,"fx":0,"col":[[0,0,0],[0,0,0],[0,0,0]]},
        {"id":1,"start":198,"stop":565,"on":true,"fx":0,"col":[[0,0,0],[0,0,0],[0,0,0]]},
        {"id":2,"start":565,"stop":922,"on":true,"fx":0,"col":[[0,0,0],[0,0,0],[0,0,0]]}
    ]}}"""

    private val presetsAfter = """{"0":{},"1":{"n":"Black","on":true,"bri":128,"seg":[
        {"id":0,"start":0,"stop":198,"fx":0},{"id":1,"start":198,"stop":565,"fx":0},{"id":2,"stop":0},{"id":3,"stop":0}
    ]}}"""

    private fun api(bootPs: Int = 1, presets: List<String> = listOf(presetsBefore, presetsAfter)): WledApiClient {
        val api = mock<WledApiClient>()
        whenever(api.downloadRaw(ip, "json/cfg")).thenReturn("""{"def":{"ps":$bootPs,"on":true,"bri":128}}""")
        whenever(api.downloadRaw(ip, "presets.json")).thenReturn(presets[0], *presets.drop(1).toTypedArray())
        whenever(api.downloadRaw(ip, "json/state")).thenReturn(state)
        whenever(api.downloadRaw(ip, "json/info")).thenReturn("""{"leds":{"maxseg":4}}""")
        return api
    }

    @Test
    fun `preset body keeps the preset's black look but takes the board's bounds and wiring`() {
        val sync = WledBootPresetSync(mock(), mapper, settleMs = 0)
        val body = sync.presetBody(1, mapper.readTree(presetsBefore).path("1"), mapper.readTree(state), 4)

        assertEquals(1, body["psave"].asInt())
        assertTrue(body["o"].asBoolean())
        assertTrue(body["sb"].asBoolean())
        assertEquals("Black", body["n"].asText())
        assertEquals(128, body["bri"].asInt())
        val seg = body["seg"]
        assertEquals(4, seg.size())
        assertEquals(0, seg[0]["start"].asInt()); assertEquals(198, seg[0]["stop"].asInt())
        assertEquals("Garage Eaves", seg[0]["n"].asText())
        assertEquals(0, seg[0]["fx"].asInt())
        assertEquals("[[0,0,0],[0,0,0],[0,0,0]]", seg[0]["col"].toString())
        assertTrue(seg[0]["rev"].asBoolean(), "wiring flags come from the board, not the preset")
        assertEquals(198, seg[1]["start"].asInt()); assertEquals(565, seg[1]["stop"].asInt())
        // Leftover ids are deleted so boot-time default segments don't survive.
        assertEquals("""{"id":2,"stop":0}""", seg[2].toString())
        assertEquals("""{"id":3,"stop":0}""", seg[3].toString())
    }

    @Test
    fun `sync saves the boot preset, restores the live state and verifies the save`() {
        val api = api()
        val result = WledBootPresetSync(api, mapper, settleMs = 0).sync(ip)

        assertEquals(WledBootPresetSync.Result.Saved(1, 2), result)
        val bodies = argumentCaptor<Map<String, Any?>>()
        verify(api, times(2)).postState(any(), bodies.capture())
        assertEquals(1, bodies.firstValue["psave"])
        val restore = bodies.secondValue
        assertFalse("ps" in restore || "pl" in restore || "lor" in restore)
        assertEquals(200, restore["bri"])
    }

    @Test
    fun `sync fails loudly when the preset did not take the new bounds`() {
        val api = api(presets = listOf(presetsBefore, presetsBefore))
        assertThrows<IllegalStateException> { WledBootPresetSync(api, mapper, settleMs = 0).sync(ip) }
    }

    @Test
    fun `no boot preset or a playlist boot preset is skipped without writing`() {
        val none = api(bootPs = 0)
        assertTrue(WledBootPresetSync(none, mapper, settleMs = 0).sync(ip) is WledBootPresetSync.Result.Skipped)
        verify(none, never()).postState(any(), any())

        val playlist = api(presets = listOf("""{"1":{"n":"Loop","playlist":{"ps":[2,3]}}}"""))
        assertTrue(WledBootPresetSync(playlist, mapper, settleMs = 0).sync(ip) is WledBootPresetSync.Result.Skipped)
        verify(playlist, never()).postState(any(), any())
    }

    @Test
    fun `turned off means no calls at all`() {
        val api = mock<WledApiClient>()
        assertTrue(WledBootPresetSync(api, mapper, enabled = false).sync(ip) is WledBootPresetSync.Result.Skipped)
        verify(api, never()).downloadRaw(any(), any())
    }
}
