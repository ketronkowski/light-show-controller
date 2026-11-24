package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.preshow.NotifyPolicy
import com.ketronkowski.xlights.preshow.PreShowBusyException
import com.ketronkowski.xlights.preshow.PreShowRun
import com.ketronkowski.xlights.preshow.PreShowService
import com.ketronkowski.xlights.preshow.PreShowStore
import com.ketronkowski.xlights.preshow.ReportRenderer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class PreShowRunRequest(
    val pass: String = "manual",
    /** Send the push and the email like the 4:50 PM run does. */
    val notify: Boolean = false,
    /** Only these layers; ["Lights"] runs the camera light test. Empty = a normal check. */
    val layers: List<String> = emptyList(),
)

data class PreShowRunSummary(
    val id: String,
    val pass: String,
    val startedAt: java.time.Instant,
    val verdict: String,
    val headline: String,
)

@RestController
@RequestMapping("/api/preshow")
class PreShowController(
    private val service: PreShowService,
    private val store: PreShowStore,
    private val renderer: ReportRenderer,
) {
    @PostMapping("/run")
    fun run(@RequestBody(required = false) req: PreShowRunRequest?): ResponseEntity<PreShowRun> {
        val r = req ?: PreShowRunRequest()
        return try {
            ResponseEntity.ok(service.run(r.pass.take(40), if (r.notify) NotifyPolicy.ALWAYS else NotifyPolicy.QUIET, r.layers.take(10), manual = true))
        } catch (e: PreShowBusyException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @GetMapping("/runs")
    fun runs(): List<PreShowRunSummary> =
        store.list(60).map { PreShowRunSummary(it.id, it.pass, it.startedAt, it.verdict.name, it.headline()) }

    @GetMapping("/runs/latest")
    fun latest(): ResponseEntity<PreShowRun> = store.latest()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/runs/{id}")
    fun get(@PathVariable id: String): ResponseEntity<PreShowRun> =
        store.get(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    /** The same HTML as the email, for the report page. */
    @GetMapping("/runs/{id}/report", produces = [MediaType.TEXT_HTML_VALUE])
    fun report(@PathVariable id: String): ResponseEntity<String> {
        val run = store.get(id) ?: return ResponseEntity.notFound().build()
        val image = if (store.attachment(id, "lights.jpg") != null) "lights.jpg" else null
        return ResponseEntity.ok(renderer.render(run, image))
    }

    /** The camera light test picture of a run. */
    @GetMapping("/runs/{id}/lights.jpg", produces = [MediaType.IMAGE_JPEG_VALUE])
    fun lightsImage(@PathVariable id: String): ResponseEntity<ByteArray> =
        store.attachment(id, "lights.jpg")?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}
