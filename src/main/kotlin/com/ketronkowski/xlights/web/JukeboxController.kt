package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.jukebox.JukeboxChecker
import com.ketronkowski.xlights.jukebox.JukeboxRun
import com.ketronkowski.xlights.jukebox.JukeboxService
import com.ketronkowski.xlights.preshow.NotifyPolicy
import com.ketronkowski.xlights.preshow.PreShowBusyException
import com.ketronkowski.xlights.preshow.PreShowService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Jukebox interrupt test: run it now, and the latest result. */
@RestController
@RequestMapping("/api/jukebox")
class JukeboxController(
    private val jukebox: JukeboxService,
    private val preshow: PreShowService,
) {
    private val log = LoggerFactory.getLogger(JukeboxController::class.java)

    /**
     * Runs the jukebox test now in the background, as a pre-show run with only the Jukebox
     * layer, so it shows up in the run list. FPP must be idle (stop the show first).
     */
    @PostMapping("/run")
    fun run(): ResponseEntity<Any> {
        if (preshow.busy || jukebox.busy) return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "a check is already running"))
        Thread.ofVirtual().name("jukebox-test-manual").start {
            try {
                preshow.run("jukebox-manual", NotifyPolicy.QUIET, listOf(JukeboxChecker.LAYER), manual = true)
            } catch (e: PreShowBusyException) {
                log.info("Manual jukebox test skipped: a check is already running")
            } catch (e: Exception) {
                log.warn("Manual jukebox test failed", e)
            }
        }
        return ResponseEntity.accepted().body(mapOf("started" to true))
    }

    @GetMapping("/last")
    fun last(): ResponseEntity<JukeboxRun> = jukebox.lastRun()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}
