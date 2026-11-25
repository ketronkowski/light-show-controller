package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.show.PlaylistSummary
import com.ketronkowski.xlights.show.ShowStatus
import com.ketronkowski.xlights.show.ShowStatusService
import com.ketronkowski.xlights.preshow.brief
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Show Control page: FPP playback, power and board status, and the FM speakers switch. */
@RestController
@RequestMapping("/api/show")
class ShowController(private val status: ShowStatusService) {

    @GetMapping("/status")
    fun status(): ShowStatus = status.status()

    data class SpeakersRequest(val on: Boolean)

    /** Turns the FM speakers on or off; returns their state afterwards. */
    @PostMapping("/speakers")
    fun speakers(@RequestBody req: SpeakersRequest): ResponseEntity<Any> = try {
        ResponseEntity.ok(status.setSpeakers(req.on))
    } catch (e: IllegalStateException) {
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to e.message))
    } catch (e: Exception) {
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("error" to "Home Assistant: ${e.brief()}"))
    }

    @GetMapping("/playlists")
    fun playlists(): ResponseEntity<List<PlaylistSummary>> = try {
        ResponseEntity.ok(status.playlists())
    } catch (e: Exception) {
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
    }
}
