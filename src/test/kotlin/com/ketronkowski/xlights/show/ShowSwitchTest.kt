package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.preshow.HomeAssistantClient
import com.ketronkowski.xlights.preshow.PreShowProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.nio.file.Path

class ShowSwitchTest {
    private val entity = "switch.front_yard_light_show_fm_speakers"
    private val props = PreShowProperties(ha = PreShowProperties.HomeAssistant(url = "http://ha", token = "t",
        switches = mapOf("Road speakers" to entity)))

    @Test
    fun `turns a configured switch on by its label and reports the new state`(@TempDir dir: Path) {
        val ha = mock<HomeAssistantClient> {
            on { configured } doReturn true
            on { state(entity) } doReturn ObjectMapper().readTree("""{"state":"on"}""")
        }
        val service = ShowStatusService(mock(), ha, props, mock(), ObjectMapper(), dir.toString())
        val sw = service.setSwitch("road speakers", true)
        verify(ha).setSwitch(entity, true)
        assertEquals("on", sw.state)
        assertEquals("Road speakers", sw.label)
    }

    @Test
    fun `refuses anything that isn't a configured switch`(@TempDir dir: Path) {
        val ha = mock<HomeAssistantClient> { on { configured } doReturn true }
        val service = ShowStatusService(mock(), ha, props, mock(), ObjectMapper(), dir.toString())
        assertThrows<NoSuchElementException> { service.setSwitch("switch.front_yard_light_show", false) }
        verify(ha, never()).setSwitch(any(), any())
    }
}
