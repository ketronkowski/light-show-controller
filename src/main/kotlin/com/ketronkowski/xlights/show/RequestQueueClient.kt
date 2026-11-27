package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.preshow.brief
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The Remote Falcon jukebox queue: the song requests waiting to play, read through the viewer
 * service's public GraphQL `getShow` (the same query the show site polls). Read-only.
 *
 * `via` (GPS = driveway, CODE = live stream page) is a field of our Remote Falcon fork. Against a
 * viewer that doesn't know it yet, the query fails schema validation, so this asks again without it
 * and stops asking for it until the app restarts.
 */
@Component
class RequestQueueClient(
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.remote-falcon.viewer-url:http://remote-falcon-viewer.remote-falcon.svc.cluster.local:8082/remote-falcon-viewer/graphql}")
    private val viewerUrl: String,
    @Value("\${xlights.remote-falcon.show:lights}") private val show: String,
) {
    private val http = FppShowClient.quickClient(Duration.ofSeconds(2), Duration.ofSeconds(4))
    @Volatile private var withVia = true

    val configured: Boolean get() = viewerUrl.isNotBlank() && show.isNotBlank()

    fun queue(): RequestQueue {
        if (!configured) return RequestQueue(configured = false)
        return try {
            var reply = post(withVia)
            if (withVia && mentionsVia(reply.path("errors"))) {
                withVia = false
                reply = post(false)
            }
            parseQueue(reply)
        } catch (e: Exception) {
            RequestQueue(configured = true, error = e.brief())
        }
    }

    private fun post(via: Boolean): JsonNode {
        val fields = "position ownerRequested ${if (via) "via " else ""}sequence { name displayName }"
        val body = mapOf(
            "query" to "query(\$s: String) { getShow(showSubdomain: \$s) { playingNext requests { $fields } } }",
            "variables" to mapOf("s" to show),
        )
        val text = http.post().uri(viewerUrl).contentType(MediaType.APPLICATION_JSON).body(body)
            .retrieve().body(String::class.java) ?: ""
        return objectMapper.readTree(text.ifBlank { "{}" })
    }

    companion object {
        private fun mentionsVia(errors: JsonNode): Boolean =
            errors.isArray && errors.any { it.path("message").asText("").contains("via", ignoreCase = true) }

        /** getShow's reply: the requests in play order, titled by their display name. */
        internal fun parseQueue(reply: JsonNode): RequestQueue {
            val errors = reply.path("errors")
            val showNode = reply.path("data").path("getShow")
            if (showNode.isMissingNode || showNode.isNull) {
                val msg = errors.firstOrNull()?.path("message")?.asText(null) ?: "show not found"
                return RequestQueue(configured = true, error = msg)
            }
            val requests = showNode.path("requests").filter { it.path("sequence").isObject }
                .sortedBy { it.path("position").asInt(Int.MAX_VALUE) }
                .map { r ->
                    val seq = r.path("sequence")
                    val name = seq.path("name").asText("")
                    QueuedRequest(
                        title = seq.path("displayName").asText("").ifBlank { name },
                        sequence = name,
                        source = when {
                            r.path("ownerRequested").asBoolean(false) -> "owner"
                            r.path("via").asText("").equals("GPS", ignoreCase = true) -> "driveway"
                            r.path("via").asText("").equals("CODE", ignoreCase = true) -> "stream"
                            else -> null
                        },
                    )
                }
            return RequestQueue(configured = true, requests = requests)
        }
    }
}
