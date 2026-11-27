package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RequestQueueTest {
    private val json = ObjectMapper()

    @Test
    fun `requests come back in play order with where they came from`() {
        val q = RequestQueueClient.parseQueue(json.readTree("""
            {"data":{"getShow":{"playingNext":"Althea","requests":[
              {"position":7,"ownerRequested":false,"via":"CODE","sequence":{"name":"Wizards","displayName":"Wizards in Winter"}},
              {"position":5,"ownerRequested":false,"via":"GPS","sequence":{"name":"Althea","displayName":""}},
              {"position":9,"ownerRequested":true,"via":null,"sequence":{"name":"Althea Test","displayName":"Althea Test"}},
              {"position":11,"ownerRequested":false,"sequence":{"name":"Old","displayName":"Old Row"}}
            ]}}}"""))
        assertNull(q.error)
        assertEquals(listOf("Althea", "Wizards in Winter", "Althea Test", "Old Row"), q.requests.map { it.title })
        assertEquals(listOf("driveway", "stream", "owner", null), q.requests.map { it.source })
    }

    @Test
    fun `an empty queue and a missing show`() {
        assertEquals(0, RequestQueueClient.parseQueue(json.readTree("""{"data":{"getShow":{"requests":[]}}}""")).requests.size)
        assertEquals(0, RequestQueueClient.parseQueue(json.readTree("""{"data":{"getShow":{"requests":null}}}""")).requests.size)
        assertEquals("show not found", RequestQueueClient.parseQueue(json.readTree("""{"data":{"getShow":null}}""")).error)
    }
}
