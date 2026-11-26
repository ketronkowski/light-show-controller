package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.lipsync.LipSyncChecker
import com.ketronkowski.xlights.lipsync.LipSyncResult
import com.ketronkowski.xlights.lipsync.LipSyncService
import com.ketronkowski.xlights.preshow.PreShowProperties
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Stream lip sync: the measurer posts its result here; the next pre-show pass reports it. */
@RestController
@RequestMapping("/api/lipsync")
class LipSyncController(
    private val service: LipSyncService,
    private val props: PreShowProperties,
) {
    @PostMapping("/result")
    fun post(@RequestBody result: LipSyncResult): ResponseEntity<Any> = try {
        ResponseEntity.ok(view(service.save(result)))
    } catch (e: IllegalArgumentException) {
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
    }

    @GetMapping("/last")
    fun last(): ResponseEntity<Any> = service.last()?.let { ResponseEntity.ok(view(it)) } ?: ResponseEntity.notFound().build()

    private fun view(r: LipSyncResult): Map<String, Any?> {
        val c = LipSyncChecker.result(r, props.lipSync)
        return mapOf("result" to r, "outcome" to c.outcome, "message" to c.message)
    }
}
