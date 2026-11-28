package com.ketronkowski.xlights.show

import com.fasterxml.jackson.databind.JsonNode
import com.ketronkowski.xlights.preshow.JsonHttp
import com.ketronkowski.xlights.preshow.PreShowProperties
import com.ketronkowski.xlights.preshow.brief
import org.springframework.stereotype.Component

/**
 * Remote Falcon's request queue, read-only, from the plugins API's GET /queue with the show token
 * (the same URL and token as the jukebox test, preshow.jukebox). Never calls /nextPlaylistInQueue,
 * which removes the song it returns.
 */
@Component
class RemoteFalconQueue(
    private val http: JsonHttp,
    private val props: PreShowProperties,
) {
    val configured: Boolean get() = props.jukebox.configured

    fun now(): QueueNow {
        if (!configured) return QueueNow(configured = false)
        val cfg = props.jukebox
        return try {
            parse(http.get(cfg.pluginsUrl.trimEnd('/') + "/queue", mapOf("remotetoken" to cfg.showToken)).body)
        } catch (e: Exception) {
            QueueNow(configured = true, error = e.brief())
        }
    }

    companion object {
        fun parse(n: JsonNode): QueueNow {
            fun text(x: JsonNode) = x.asText("").takeIf { !x.isNull && it.isNotBlank() }
            val items = n.path("requests").takeIf { it.isArray }?.mapNotNull { r ->
                val seq = text(r.path("sequence")) ?: return@mapNotNull null
                QueuedSong(
                    title = text(r.path("displayName")) ?: seq,
                    sequence = seq,
                    owner = r.path("ownerRequested").asBoolean(false),
                    song = r.path("song").asBoolean(true),
                )
            } ?: emptyList()
            return QueueNow(configured = true, playingNow = text(n.path("playingNow")), items = items)
        }
    }
}
