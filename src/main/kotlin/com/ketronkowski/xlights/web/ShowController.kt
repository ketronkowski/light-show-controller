package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.show.PlaylistSummary
import com.ketronkowski.xlights.show.ShowStatus
import com.ketronkowski.xlights.show.ShowStatusService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import com.ketronkowski.xlights.preshow.brief
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Show Control page: FPP playback, power and board status, plus the switches it may flip (road speakers). */
@RestController
@RequestMapping("/api/show")
class ShowController(private val status: ShowStatusService) {

    @GetMapping("/status")
    fun status(): ShowStatus = status.status()

    @GetMapping("/playlists")
    fun playlists(): ResponseEntity<List<PlaylistSummary>> = try {
        ResponseEntity.ok(status.playlists())
    } catch (e: Exception) {
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
    }

    /** Turns a configured switch on or off: `{"label": "Road speakers", "on": true}`. */
    @PostMapping("/switch")
    fun switch(@RequestBody req: SwitchRequest): ResponseEntity<Any> = try {
        ResponseEntity.ok(status.setSwitch(req.label, req.on))
    } catch (e: NoSuchElementException) {
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))
    } catch (e: IllegalStateException) {
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))
    } catch (e: Exception) {
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("error" to "Home Assistant: ${e.brief()}"))
    }

    data class SwitchRequest(val label: String, val on: Boolean)
}
