package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.show.PlaylistSummary
import com.ketronkowski.xlights.show.ShowStatus
import com.ketronkowski.xlights.show.ShowStatusService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Show Control page: FPP playback, power and board status. Read-only for now. */
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
}
