package com.ketronkowski.xlights.lighttest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

/** One xLights model to light: its FPP channels and where its pixels sit on the board. */
data class ModelTarget(
    val name: String,
    val controller: String,
    /** 1-based, inclusive, in FPP's channel space. */
    val firstChannel: Int,
    val lastChannel: Int,
    /** 1-based output on the board, when the board's outputs could be read. */
    val port: Int? = null,
    /** The board's address, so the test can switch it on if it's off. */
    val boardIp: String? = null,
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val where: String get() = if (port != null) "$controller port $port" else controller
}

/** What calibration learned about one model: where it shows up in the camera picture. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ModelCal(
    val name: String,
    val controller: String,
    val port: Int? = null,
    val firstChannel: Int,
    val lastChannel: Int,
    val visible: Boolean,
    val mask: Mask = Mask(emptyList()),
    /** Total brightness gain inside the mask on calibration night. */
    val reference: Double = 0.0,
    /** For a model not in view: how many clean pixels it did reach (helps tune the threshold). */
    val seenPixels: Int? = null,
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val where: String get() = if (port != null) "$controller port $port" else controller
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Calibration(
    val createdAt: Instant,
    val width: Int,
    val height: Int,
    val color: String,
    val models: List<ModelCal>,
    /** Models that could not be lit at all (no FPP output to their board, ...) and why. */
    val skipped: Map<String, String> = emptyMap(),
    /** Pixels that flickered between dark pictures; never counted. */
    val noise: Mask = Mask(emptyList()),
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val visible: List<ModelCal> get() = models.filter { it.visible }
}

enum class LightStatus { ON, DIM, OFF, NOT_IN_VIEW }

@JsonIgnoreProperties(ignoreUnknown = true)
data class ModelResult(
    val name: String,
    val controller: String,
    val port: Int? = null,
    val status: LightStatus,
    /** Brightness gain against calibration, scaled so the night's typical model is 1.0. */
    val level: Double? = null,
    /** Share of the model's calibrated area that lit up, scaled the same way. */
    val area: Double? = null,
    /** First FPP channel, which gives the wiring order along a port. */
    val firstChannel: Int? = null,
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val where: String get() = if (port != null) "$controller port $port" else controller
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LightRun(
    val runId: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val results: List<ModelResult>,
    /** Set when the test couldn't run (FPP playing, not calibrated, ...). */
    val skippedBecause: String? = null,
    val calibratedAt: Instant? = null,
    /** Typical gain against calibration before scaling; far under 1 means a dark or moved view. */
    val typicalLevel: Double? = null,
)

/** Progress of a calibration or manual test, for the page. */
data class LightTestStatus(
    val busy: Boolean,
    val job: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val calibratedAt: Instant? = null,
    val calibratedModels: Int = 0,
    val visibleModels: Int = 0,
    val configured: Boolean = false,
)
