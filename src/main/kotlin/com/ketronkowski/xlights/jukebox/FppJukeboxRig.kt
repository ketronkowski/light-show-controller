package com.ketronkowski.xlights.jukebox

import com.fasterxml.jackson.databind.JsonNode
import com.ketronkowski.xlights.preshow.JsonHttp
import com.ketronkowski.xlights.preshow.PreShowProperties
import java.util.Base64

/** The real rig: FPP's REST API and the Remote Falcon plugins API. */
class FppJukeboxRig(
    private val http: JsonHttp,
    private val fppHost: String,
    private val cfg: PreShowProperties.JukeboxTest,
    fppUser: String,
    fppPassword: String,
) : JukeboxRig {
    private val fppAuth: Map<String, String> =
        if (fppPassword.isEmpty()) emptyMap()
        else mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$fppUser:$fppPassword".toByteArray()))
    private val rfAuth get() = mapOf("remotetoken" to cfg.showToken)
    private val rf get() = cfg.pluginsUrl.trimEnd('/')
    private val fpp get() = "http://$fppHost"

    override fun state(): PlayState = parseState(http.get("$fpp/api/fppd/status", fppAuth).body)

    override fun startPlaylist(name: String) {
        // Start Playlist: name, repeat, only-if-not-running
        command("Start Playlist", listOf(name, "true", "false"))
    }

    override fun stopNow() {
        http.get("$fpp/api/playlists/stop", fppAuth)
    }

    override fun volume(): Int? = http.get("$fpp/api/system/volume", fppAuth).body.path("volume").takeIf { !it.isMissingNode }?.asInt()

    override fun setVolume(volume: Int) {
        http.post("$fpp/api/system/volume", mapOf("volume" to volume), fppAuth)
    }

    override fun request(sequence: String) {
        http.post("$rf/addTestRequest", mapOf("sequence" to sequence), rfAuth)
    }

    override fun purgeQueue() {
        http.delete("$rf/purgeQueue", rfAuth)
    }

    private fun command(name: String, args: List<String>) {
        http.post("$fpp/api/command", mapOf("command" to name, "args" to args), fppAuth)
    }

    companion object {
        fun parseState(s: JsonNode): PlayState {
            fun text(n: JsonNode) = n.asText("").takeIf { it.isNotBlank() }
            return PlayState(
                status = s.path("status_name").asText("unknown"),
                playlist = text(s.path("current_playlist").path("playlist")),
                sequence = text(s.path("current_sequence"))?.removeSuffix(".fseq"),
                secondsPlayed = s.path("seconds_played").asInt(0),
                secondsRemaining = s.path("seconds_remaining").asInt(0),
            )
        }
    }
}
