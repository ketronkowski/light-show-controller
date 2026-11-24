package com.ketronkowski.xlights.show

import java.time.Instant

/** Everything the Show Control page shows, in one reply. */
data class ShowStatus(
    val at: Instant,
    val fpp: FppNow,
    val power: PowerNow,
    val boards: List<BoardNow>,
    /** False on the LAN host without a client certificate, and wherever no login is wired yet. */
    val canControl: Boolean = false,
)

data class FppNow(
    val host: String?,
    val reachable: Boolean,
    val error: String? = null,
    val version: String? = null,
    /** FPP's status_name: idle, playing, paused, stopping gracefully, ... */
    val status: String? = null,
    val playlist: String? = null,
    /** 1-based position in the playlist, as FPP reports it. */
    val index: Int? = null,
    val count: Int? = null,
    val sequence: String? = null,
    val song: String? = null,
    val secondsPlayed: Int? = null,
    val secondsRemaining: Int? = null,
    val volume: Int? = null,
    val schedulerEnabled: Boolean? = null,
    /** What the scheduler is running now, if anything. */
    val scheduledPlaylist: String? = null,
    val scheduledEnd: String? = null,
    val nextPlaylist: String? = null,
    val nextStart: String? = null,
    val cpuTempC: Double? = null,
    val mediaFreeGb: Double? = null,
    val mediaFreePercent: Double? = null,
    val powerBad: Boolean = false,
    val expectedVolume: Int? = null,
)

data class PowerNow(
    val configured: Boolean,
    val circuit: CircuitNow? = null,
    val plugs: List<PlugNow> = emptyList(),
    /** Show circuit watts, oldest first, one point per sample (about every 30 s), up to an hour. */
    val history: List<PowerSample> = emptyList(),
)

data class CircuitNow(
    val entity: String,
    /** on, off, unavailable, or null when no relay entity is configured. */
    val state: String?,
    val watts: Double?,
    /** Measured amps when a current sensor is configured, else watts / 120. */
    val amps: Double?,
    val ampsEstimated: Boolean,
    val volts: Double?,
    val error: String? = null,
)

data class PlugNow(
    val label: String,
    val entity: String,
    val state: String,
    val watts: Double? = null,
    /** xLights controller names this plug powers. */
    val boards: List<String>,
)

/** One show-circuit reading. [amps] is measured when a current sensor is set, else watts ÷ volts. */
data class PowerSample(val at: Instant, val watts: Double, val amps: Double? = null, val volts: Double? = null)

data class BoardNow(
    val name: String,
    val ip: String,
    val reachable: Boolean,
    /** True while the board is taking realtime (FPP) data. */
    val live: Boolean = false,
    /** WLED's estimated current draw in mA (from the brightness limiter); 0 when black. */
    val milliamps: Int? = null,
    val maxMilliamps: Int? = null,
    val on: Boolean? = null,
    val brightness: Int? = null,
    val plug: String? = null,
    val error: String? = null,
)

data class PlaylistSummary(val name: String, val items: List<PlaylistItem>, val error: String? = null)

data class PlaylistItem(
    /** 0-based position in the main section. */
    val position: Int,
    val type: String,
    val title: String,
    val sequence: String? = null,
    val media: String? = null,
    val seconds: Int? = null,
)
