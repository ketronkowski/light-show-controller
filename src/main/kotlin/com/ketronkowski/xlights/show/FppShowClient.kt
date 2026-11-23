package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.preshow.brief
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64

/**
 * Falcon Player for the Show Control page. Short timeouts, because the page polls:
 * a slow FPP must not hold the status reply for 30 s.
 */
@Component
class FppShowClient(
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.fpp.username:admin}") private val username: String,
    @Value("\${xlights.fpp.password:}") private val password: String,
) {
    private val http: RestClient = quickClient(Duration.ofSeconds(2), Duration.ofSeconds(4))

    private val auth: Map<String, String>
        get() = if (password.isEmpty()) emptyMap()
        else mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray()))

    fun get(host: String, path: String): JsonNode {
        val req = http.get().uri("http://$host/api$path")
        auth.forEach { (k, v) -> req.header(k, v) }
        val text = req.retrieve().body(String::class.java) ?: ""
        return if (text.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(text)
    }

    fun playlistNames(host: String): List<String> = get(host, "/playlists").mapNotNull { it.asText(null) }

    fun playlist(host: String, name: String): PlaylistSummary = try {
        parsePlaylist(name, get(host, "/playlist/" + URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")))
    } catch (e: Exception) {
        PlaylistSummary(name, emptyList(), e.brief())
    }

    companion object {
        fun quickClient(connect: Duration, read: Duration): RestClient {
            // HTTP/1.1 only, as in AppConfig: FPP drops connections that ask to upgrade to h2c.
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connect).build()
            return RestClient.builder()
                .requestFactory(JdkClientHttpRequestFactory(client).apply { setReadTimeout(read) })
                .build()
        }

        /** FPP's playlist JSON: the main section's entries, titled by their media or sequence name. */
        internal fun parsePlaylist(name: String, pl: JsonNode): PlaylistSummary {
            val items = pl.path("mainPlaylist").mapIndexed { i, it ->
                val type = it.path("type").asText("")
                val seq = it.path("sequenceName").asText("").ifBlank { null }
                val media = it.path("mediaName").asText("").ifBlank { null }
                val title = when (type) {
                    "pause" -> "Pause " + it.path("duration").asInt(0) + " s"
                    "playlist" -> "Playlist " + it.path("name").asText("")
                    else -> stripExt(media ?: seq) ?: it.path("note").asText("").ifBlank { type }
                }
                PlaylistItem(
                    position = i,
                    type = type,
                    title = title,
                    sequence = seq,
                    media = media,
                    seconds = it.path("duration").asDouble(0.0).toInt().takeIf { s -> s > 0 },
                )
            }
            return PlaylistSummary(pl.path("name").asText(name).ifBlank { name }, items)
        }

        internal fun stripExt(file: String?): String? =
            file?.substringAfterLast('/')?.let { f -> if ('.' in f) f.substringBeforeLast('.') else f }?.ifBlank { null }
    }
}
