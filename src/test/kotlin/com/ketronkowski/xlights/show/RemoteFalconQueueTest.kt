package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.preshow.JsonHttp
import com.ketronkowski.xlights.preshow.JsonReply
import com.ketronkowski.xlights.preshow.PreShowProperties
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RemoteFalconQueueTest {
    private val json = ObjectMapper()
    private val props = PreShowProperties(jukebox = PreShowProperties.JukeboxTest(pluginsUrl = "https://plugins.example/", showToken = "tok"))

    @Test
    fun `reads the queue in order, with display names, PSAs and test requests marked`() {
        val body = json.readTree("""{"playingNow":"Song A","playingNext":"x","requests":[
            {"position":1,"sequence":"first","displayName":"First Song","ownerRequested":false,"song":true},
            {"position":2,"sequence":"Dana-Farber PSA","displayName":null,"ownerRequested":true,"song":false},
            {"position":3,"sequence":"Althea Test","displayName":"","ownerRequested":true,"song":true},
            {"position":4}]}""")
        val http = mock<JsonHttp> { on { get(eq("https://plugins.example/queue"), eq(mapOf("remotetoken" to "tok"))) } doReturn JsonReply(body, null) }
        val q = RemoteFalconQueue(http, props).now()
        assertEquals("Song A", q.playingNow)
        assertNull(q.error)
        assertEquals(listOf(
            QueuedSong("First Song", "first", owner = false, song = true),
            QueuedSong("Dana-Farber PSA", "Dana-Farber PSA", owner = true, song = false),
            QueuedSong("Althea Test", "Althea Test", owner = true, song = true)), q.items)
    }

    @Test
    fun `an error is reported, not thrown`() {
        val http = mock<JsonHttp> { on { get(any(), any()) } doThrow RuntimeException("404 Not Found") }
        val q = RemoteFalconQueue(http, props).now()
        assertNotNull(q.error)
        assertEquals(emptyList(), q.items)
    }

    @Test
    fun `not configured without a plugins URL and token`() {
        val http = mock<JsonHttp>()
        val q = RemoteFalconQueue(http, PreShowProperties()).now()
        assertFalse(q.configured)
        verify(http, never()).get(any(), any())
    }
}
