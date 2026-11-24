package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.lighttest.LightRun
import com.ketronkowski.xlights.lighttest.LightTestBusyException
import com.ketronkowski.xlights.lighttest.LightTestService
import com.ketronkowski.xlights.lighttest.LightTestStatus
import com.ketronkowski.xlights.preshow.NotifyPolicy
import com.ketronkowski.xlights.preshow.PreShowBusyException
import com.ketronkowski.xlights.preshow.PreShowService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Camera light test: calibrate, run now, stop, and the pictures. */
@RestController
@RequestMapping("/api/lighttest")
class LightTestController(
    private val lights: LightTestService,
    private val preshow: PreShowService,
) {
    private val log = LoggerFactory.getLogger(LightTestController::class.java)

    @GetMapping("/status")
    fun status(): LightTestStatus = lights.status()

    /**
     * Starts calibration in the background (lights every model once, a few minutes).
     * `?missing=true` relights only the models the camera couldn't see last time and merges them in.
     */
    @PostMapping("/calibrate")
    fun calibrate(@RequestParam(defaultValue = "false") missing: Boolean): ResponseEntity<Any> = try {
        ResponseEntity.accepted().body(lights.startCalibration(missingOnly = missing))
    } catch (e: LightTestBusyException) {
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))
    } catch (e: IllegalStateException) {
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))
    }

    /**
     * Runs the camera pass now in the background, as a pre-show run with only the
     * Lights layer, so it shows up in the run list with its picture.
     */
    @PostMapping("/run")
    fun run(): ResponseEntity<Any> {
        if (preshow.busy || lights.status().busy) return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "a check is already running"))
        Thread.ofVirtual().name("light-test-manual").start {
            try {
                preshow.run("camera-manual", NotifyPolicy.QUIET, listOf("Lights"), manual = true)
            } catch (e: PreShowBusyException) {
                log.info("Manual light test skipped: a check is already running")
            } catch (e: Exception) {
                log.warn("Manual light test failed", e)
            }
        }
        return ResponseEntity.accepted().body(mapOf("started" to true))
    }

    /** Stops a calibration or test after the current model; FPP test mode is switched off. */
    @PostMapping("/stop")
    fun stop(): LightTestStatus {
        lights.requestAbort()
        return lights.status()
    }

    @GetMapping("/calibration.jpg", produces = [MediaType.IMAGE_JPEG_VALUE])
    fun calibrationImage(): ResponseEntity<ByteArray> =
        lights.calibrationImage()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    /** Which models calibration found in the camera's view, and which it didn't. */
    @GetMapping("/calibration")
    fun calibration(): ResponseEntity<Map<String, Any?>> {
        val cal = lights.calibration() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf(
            "createdAt" to cal.createdAt,
            "visible" to cal.visible.map { mapOf("name" to it.name, "where" to it.where, "pixels" to it.mask.size) },
            "notInView" to cal.models.filter { !it.visible }.map { mapOf("name" to it.name, "where" to it.where) },
            "skipped" to cal.skipped,
        ))
    }

    @GetMapping("/last")
    fun last(): ResponseEntity<LightRun> = lights.lastRun()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}
