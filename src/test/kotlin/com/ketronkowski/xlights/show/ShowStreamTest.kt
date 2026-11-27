package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.preshow.HomeAssistantClient
import com.ketronkowski.xlights.preshow.PreShowProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import java.nio.file.Path
import java.time.Instant

class ShowStreamTest {
    private val json = ObjectMapper()
    private val props = PreShowProperties(ha = PreShowProperties.HomeAssistant(url = "http://ha", token = "t",
        streamViewersEntity = "sensor.live_stream_viewers", streamUploadEntity = "sensor.live_stream_upload",
        feedSourceEntity = "sensor.live_feed_source", streamAudioEntity = "binary_sensor.live_stream_audio"))

    @Test
    fun `reads viewers, upload, picture and sound from Home Assistant`(@TempDir dir: Path) {
        val ha = mock<HomeAssistantClient> {
            on { configured } doReturn true
            on { state("sensor.live_stream_viewers") } doReturn json.readTree(
                """{"state":"7","last_reported":"2026-11-26T22:40:11.1+00:00"}""")
            on { state("sensor.live_stream_upload") } doReturn json.readTree(
                """{"state":"13.31","last_reported":"2026-11-26T22:40:12+00:00"}""")
            on { state("sensor.live_feed_source") } doReturn json.readTree("""{"state":"phone"}""")
            on { state("binary_sensor.live_stream_audio") } doReturn json.readTree("""{"state":"on"}""")
        }
        val s = ShowStatusService(mock(), ha, props, mock(), json, dir.toString()).streamNow()
        assertTrue(s.configured)
        assertEquals(7, s.viewers)
        assertEquals(13.31, s.uploadMbps)
        assertEquals("phone", s.feedSource)
        assertEquals("on", s.audio)
        assertEquals(Instant.parse("2026-11-26T22:40:12Z"), s.updated)
        assertNull(s.byLink, "no attribute: unknown, not nobody")
    }

    @Test
    fun `lists the households watching from the viewers_by_link attribute`(@TempDir dir: Path) {
        val ha = mock<HomeAssistantClient> {
            on { configured } doReturn true
            on { state("sensor.live_stream_viewers") } doReturn json.readTree(
                """{"state":"4","attributes":{"viewers_by_link":[{"label":"Smith","screens":2},
                   {"label":"Jones","screens":1},{"label":"","screens":1},{"label":"Gone","screens":0}]}}""")
        }
        val s = ShowStatusService(mock(), ha, props, mock(), json, dir.toString()).streamNow()
        assertEquals(listOf(LinkViewers("Smith", 2), LinkViewers("Jones", 1)), s.byLink)
    }

    @Test
    fun `an empty viewers_by_link means nobody on a link`(@TempDir dir: Path) {
        val ha = mock<HomeAssistantClient> {
            on { configured } doReturn true
            on { state("sensor.live_stream_viewers") } doReturn json.readTree(
                """{"state":"1","attributes":{"viewers_by_link":[]}}""")
        }
        assertEquals(emptyList<LinkViewers>(), ShowStatusService(mock(), ha, props, mock(), json, dir.toString()).streamNow().byLink)
    }

    @Test
    fun `unknown or missing sensors read as null, not zero`(@TempDir dir: Path) {
        val ha = mock<HomeAssistantClient> {
            on { configured } doReturn true
            on { state("sensor.live_stream_viewers") } doReturn json.readTree("""{"state":"unknown"}""")
            on { state("sensor.live_stream_upload") } doThrow RuntimeException("404")
        }
        val s = ShowStatusService(mock(), ha, props, mock(), json, dir.toString()).streamNow()
        assertTrue(s.configured)
        assertNull(s.viewers)
        assertNull(s.uploadMbps)
    }

    @Test
    fun `no stream entities means no stream card`(@TempDir dir: Path) {
        val ha = mock<HomeAssistantClient> { on { configured } doReturn true }
        val bare = PreShowProperties(ha = PreShowProperties.HomeAssistant(url = "http://ha", token = "t"))
        assertFalse(ShowStatusService(mock(), ha, bare, mock(), json, dir.toString()).streamNow().configured)
    }
}
