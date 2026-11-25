package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Talks to Home Assistant's REST API with a long-lived access token. */
@Component
class HomeAssistantClient(
    private val http: JsonHttp,
    private val props: PreShowProperties,
) {
    private val log = LoggerFactory.getLogger(HomeAssistantClient::class.java)
    private val ha get() = props.ha

    val configured: Boolean get() = ha.url.isNotBlank() && ha.token.isNotBlank()

    private val auth get() = mapOf("Authorization" to "Bearer ${ha.token}")
    private val base get() = ha.url.trimEnd('/')

    /** The entity's state object (`state`, `attributes`, `last_changed`, ...). */
    fun state(entityId: String): JsonNode = http.get("$base/api/states/$entityId", auth).body

    fun setState(entityId: String, state: String, attributes: Map<String, Any?>) {
        http.post("$base/api/states/$entityId", mapOf("state" to state, "attributes" to attributes), auth)
    }

    /** Turns a switch on or off (`switch.turn_on` / `switch.turn_off`). Errors are thrown. */
    fun setSwitch(entityId: String, on: Boolean) {
        val domain = entityId.substringBefore('.', "switch")
        http.post("$base/api/services/$domain/${if (on) "turn_on" else "turn_off"}", mapOf("entity_id" to entityId), auth)
    }

    /** Sends a push through a notify service; never throws. */
    fun notify(title: String, message: String, url: String? = null): Boolean {
        if (!configured || ha.notifyService.isBlank()) return false
        val data = mutableMapOf<String, Any>("tag" to "preshow", "group" to "preshow")
        if (!url.isNullOrBlank()) data["clickAction"] = url
        return runCatching {
            http.post(
                "$base/api/services/notify/${ha.notifyService}",
                mapOf("title" to title, "message" to message, "data" to data),
                auth,
            )
            true
        }.onFailure { log.warn("Pre-show push failed: {}", it.brief()) }.getOrDefault(false)
    }
}
