package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A JSON reply plus the server's clock (from its Date header), when it sent one. */
data class JsonReply(val body: JsonNode, val serverTime: Instant?)

/**
 * Small JSON-over-HTTP helper for the pre-show checks. Errors are thrown as-is
 * so each check can turn them into a readable message.
 */
@Component
class JsonHttp(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) {
    fun get(url: String, headers: Map<String, String> = emptyMap()): JsonReply {
        val req = restClient.get().uri(url).accept(MediaType.APPLICATION_JSON)
        headers.forEach { (k, v) -> req.header(k, v) }
        val entity = req.retrieve().toEntity(String::class.java)
        val text = entity.body ?: ""
        val node = if (text.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(text)
        return JsonReply(node, parseDate(entity.headers.getFirst(HttpHeaders.DATE)))
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val req = restClient.get().uri(url)
        headers.forEach { (k, v) -> req.header(k, v) }
        return req.exchange { _, res ->
            res.statusCode.value() to (runCatching { res.body.readAllBytes().toString(Charsets.UTF_8) }.getOrDefault(""))
        }
    }

    fun post(url: String, body: Any, headers: Map<String, String> = emptyMap()): JsonNode {
        val req = restClient.post().uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
        headers.forEach { (k, v) -> req.header(k, v) }
        return parseLenient(req.retrieve().body(String::class.java) ?: "")
    }

    /** JSON when the reply is JSON; plain-text replies (FPP commands answer "Started", "Stopped") as a text node. */
    internal fun parseLenient(text: String): JsonNode {
        val t = text.trim()
        if (t.isEmpty()) return objectMapper.createObjectNode()
        if (t.startsWith("{") || t.startsWith("[")) return runCatching { objectMapper.readTree(t) }.getOrElse { objectMapper.nodeFactory.textNode(t) }
        return objectMapper.nodeFactory.textNode(t)
    }

    private fun parseDate(value: String?): Instant? =
        value?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
}

/** A short, readable reason for an exception, for check messages. */
fun Throwable.brief(): String {
    if (this is org.springframework.web.client.HttpStatusCodeException) return "HTTP ${statusCode.value()}"
    if (this is OutOfMemoryError) return "out of memory"
    val root = generateSequence(this) { it.cause }.last()
    return when (root) {
        is java.net.UnknownHostException -> "name not found"
        is java.net.http.HttpConnectTimeoutException, is java.net.SocketTimeoutException,
        is java.net.http.HttpTimeoutException -> "timed out"
        is java.net.ConnectException -> root.message?.takeIf { it.isNotBlank() && it != "null" } ?: "connection failed"
        is java.net.NoRouteToHostException -> "no route to host"
        else -> (root.message?.takeIf { it.isNotBlank() && it != "null" } ?: message ?: root.javaClass.simpleName)
            .lineSequence().first().take(160)
    }
}
