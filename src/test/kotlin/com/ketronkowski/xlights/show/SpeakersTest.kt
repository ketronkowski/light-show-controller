package com.ketronkowski.xlights.show

import com.ketronkowski.xlights.preshow.HomeAssistantClient
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.json
import com.ketronkowski.xlights.preshow.mapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

class SpeakersTest {

    @TempDir lateinit var dir: Path

    private val entity = "switch.front_yard_light_show_fm_speakers"

    private fun service(ha: HomeAssistantClient, speakersEntity: String = entity) = ShowStatusService(
        fpp = mock(),
        ha = ha,
        props = PreShowProperties(ha = PreShowProperties.HomeAssistant(url = "http://ha", token = "t", speakersEntity = speakersEntity)),
        parser = mock(),
        objectMapper = mapper,
        dataDir = dir.toString(),
    )

    private fun ha(state: String): HomeAssistantClient = mock {
        on { configured } doReturn true
        on { state(entity) } doReturn json("""{"state":"$state"}""")
    }

    @Test
    fun `turning the speakers on calls switch turn_on and returns the new state`() {
        val ha = ha("on")
        val now = service(ha).setSpeakers(true)
        verify(ha).callService("switch", "turn_on", entity)
        assertEquals(SpeakersNow(entity, "on"), now)
    }

    @Test
    fun `turning the speakers off calls switch turn_off`() {
        val ha = ha("off")
        assertEquals("off", service(ha).setSpeakers(false).state)
        verify(ha).callService("switch", "turn_off", entity)
    }

    @Test
    fun `no speakers switch configured refuses and calls nothing`() {
        val ha = ha("on")
        assertThrows<IllegalStateException> { service(ha, speakersEntity = "").setSpeakers(true) }
        verify(ha, never()).callService(any(), any(), any())
    }

    @Test
    fun `an unavailable unknown or unreadable switch is refused and nothing is sent`() {
        for (state in listOf("unavailable", "unknown")) {
            val ha = ha(state)
            val e = assertThrows<IllegalStateException> { service(ha).setSpeakers(true) }
            assertEquals("the speakers switch is $state in Home Assistant", e.message)
            verify(ha, never()).callService(any(), any(), any())
        }
        val unreadable: HomeAssistantClient = mock {
            on { configured } doReturn true
            on { state(entity) } doThrow RuntimeException("connection refused")
        }
        val e = assertThrows<IllegalStateException> { service(unreadable).setSpeakers(false) }
        assertEquals("the speakers switch is unreadable in Home Assistant", e.message)
        verify(unreadable, never()).callService(any(), any(), any())
    }

    @Test
    fun `power status includes the speakers only when configured`() {
        val ha = ha("off")
        whenever(ha.state(any())).thenReturn(json("""{"state":"off"}"""))
        assertEquals(SpeakersNow(entity, "off"), service(ha).status().power.speakers)
        assertNull(service(ha, speakersEntity = "").status().power.speakers)
    }
}
