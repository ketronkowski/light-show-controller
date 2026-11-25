package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.lighttest.LightRun
import com.ketronkowski.xlights.lighttest.LightTestBusyException
import com.ketronkowski.xlights.lighttest.LightTestService
import com.ketronkowski.xlights.lighttest.LightTestStatus
import com.ketronkowski.xlights.lighttest.LightStatus
import com.ketronkowski.xlights.lighttest.LightTestChecker
import com.ketronkowski.xlights.lighttest.ModelPhotos
import com.ketronkowski.xlights.preshow.PreShowProperties
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
    private val props: PreShowProperties,
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

    @GetMapping("/last.jpg", produces = [MediaType.IMAGE_JPEG_VALUE])
    fun lastImage(): ResponseEntity<ByteArray> =
        lights.lastRunImage()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    /** What the camera saw while [model] alone was lit, in the latest calibration or test. */
    @GetMapping("/photo", produces = [MediaType.IMAGE_JPEG_VALUE])
    fun photo(@RequestParam job: String, @RequestParam model: String): ResponseEntity<ByteArray> {
        if (job !in ModelPhotos.JOBS) return ResponseEntity.badRequest().build()
        return lights.photos.get(job, model)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    /**
     * The latest test and calibration, model by model, for the Latest light test page:
     * each model's status, whether it has a picture, and where calibration found it in
     * the picture (x0, y0, x1, y1 as fractions, so the page can box it at any size).
     */
    @GetMapping("/latest")
    fun latest(): Map<String, Any?> {
        val cal = lights.calibration()
        val boxes = cal?.models?.filter { it.visible }?.associate { m ->
            val b = m.mask.bounds(cal.width)
            m.name to if (b[2] < 0) null else listOf(
                b[0].toDouble() / cal.width, b[1].toDouble() / cal.height,
                (b[2] + 1).toDouble() / cal.width, (b[3] + 1).toDouble() / cal.height)
        } ?: emptyMap()
        val soft = props.lights.softModels.map { LightTestChecker.globRegex(it) }
        fun isSoft(name: String) = soft.any { it.matches(name) }
        val run = lights.lastRun()
        return mapOf(
            "calibration" to cal?.let { c ->
                mapOf(
                    "createdAt" to c.createdAt,
                    "models" to c.models.map { m ->
                        mapOf("name" to m.name, "where" to m.where, "visible" to m.visible, "soft" to isSoft(m.name),
                            "box" to boxes[m.name], "photo" to lights.photos.has("calibration", m.name))
                    },
                    "skipped" to c.skipped,
                )
            },
            "test" to run?.let { r ->
                mapOf(
                    "startedAt" to r.startedAt,
                    "finishedAt" to r.finishedAt,
                    "skippedBecause" to r.skippedBecause,
                    "calibratedAt" to r.calibratedAt,
                    "image" to (lights.lastRunImage() != null),
                    "results" to r.results.map { m ->
                        mapOf("name" to m.name, "where" to m.where, "status" to m.status, "level" to m.level,
                            "soft" to isSoft(m.name), "box" to boxes[m.name],
                            "photo" to (m.status != LightStatus.NOT_IN_VIEW && lights.photos.has("test", m.name)))
                    },
                )
            },
        )
    }
}
