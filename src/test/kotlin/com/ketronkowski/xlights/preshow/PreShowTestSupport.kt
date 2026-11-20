package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.ketronkowski.xlights.domain.XLightsController
import org.springframework.web.client.RestClient
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

internal fun json(s: String): JsonNode = mapper.readTree(s)

internal fun http() = JsonHttp(RestClient.create(), mapper)

internal val NY: ZoneId = ZoneId.of("America/New_York")

/** 2026-11-26 at the given Eastern time. */
internal fun at(hour: Int, minute: Int = 0): Instant =
    java.time.LocalDate.of(2026, 11, 26).atTime(hour, minute).atZone(NY).toInstant()

internal fun board(name: String, ip: String, channels: Int = 900) =
    XLightsController(name = name, ipAddress = ip, protocol = "DDP", totalChannels = channels, models = emptyList(), vendor = "WLED")

internal fun ctx(now: Instant = at(14), controllers: List<XLightsController> = emptyList(), fpp: String? = "192.168.6.10") =
    CheckContext(controllers, Path.of("/nonexistent"), now, NY, LocalTime.of(17, 0), fpp)
