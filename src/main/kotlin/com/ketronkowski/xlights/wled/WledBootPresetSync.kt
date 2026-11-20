package com.ketronkowski.xlights.wled

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Keeps a board's boot preset in step with its segments.
 *
 * WLED does not store segments as settings: on every power-up it rebuilds default
 * segments (one per output, or one for the whole strip) and then applies the boot
 * preset. A boot preset saved with segment bounds puts its own segments back, so
 * segments set by this app are lost at the next reboot unless the boot preset is
 * saved again with them. This re-saves the boot preset with the board's current
 * segment bounds, keeping the preset's own look (name, brightness, effect, colors).
 *
 * WLED applies a preset live while saving it through the JSON API, so the board's
 * state from just before is put back afterwards. While FPP is streaming, live data
 * overrides segment output, so nothing shows on the props either way.
 */
@Component
class WledBootPresetSync(
    private val apiClient: WledApiClient,
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.boot-preset-sync:true}") private val enabled: Boolean = true,
    @Value("\${xlights.boot-preset-sync-settle-ms:1500}") private val settleMs: Long = 1500,
) {
    private val log = LoggerFactory.getLogger(WledBootPresetSync::class.java)

    sealed interface Result {
        data class Saved(val preset: Int, val segments: Int) : Result
        data class Skipped(val reason: String) : Result
    }

    /** Re-saves the boot preset of the board at [ip] with its current segments. Throws on failure. */
    fun sync(ip: String): Result {
        if (!enabled) return Result.Skipped("boot preset sync is turned off")

        val cfg = objectMapper.readTree(apiClient.downloadRaw(ip, "json/cfg"))
        val bootPs = cfg.path("def").path("ps").asInt(0)
        if (bootPs !in 1..250) return Result.Skipped("no boot preset set")

        val presets = objectMapper.readTree(apiClient.downloadRaw(ip, "presets.json"))
        val preset = presets.path(bootPs.toString())
        if (preset.has("playlist")) return Result.Skipped("boot preset $bootPs is a playlist")

        val stateText = apiClient.downloadRaw(ip, "json/state")
        val state = objectMapper.readTree(stateText)
        val maxSeg = objectMapper.readTree(apiClient.downloadRaw(ip, "json/info")).path("leds").path("maxseg").asInt(32)

        val body = presetBody(bootPs, preset, state, maxSeg)
        log.info("Saving boot preset {} on {} with {} segment(s)", bootPs, ip, state.path("seg").size())
        @Suppress("UNCHECKED_CAST")
        apiClient.postState(ip, objectMapper.convertValue(body, Map::class.java) as Map<String, Any?>)
        Thread.sleep(settleMs)
        apiClient.postState(ip, restoreBody(state))

        val saved = objectMapper.readTree(apiClient.downloadRaw(ip, "presets.json")).path(bootPs.toString())
        check(boundsMatch(saved, state)) { "boot preset $bootPs did not save the new segments" }
        return Result.Saved(bootPs, state.path("seg").size())
    }

    /**
     * The JSON state body that saves preset [ps] verbatim ("o": true): the preset's own
     * look applied to every current segment, with the current bounds, and every other
     * segment id deleted ("stop": 0) so boot-time default segments don't survive.
     */
    internal fun presetBody(ps: Int, preset: JsonNode, state: JsonNode, maxSeg: Int): ObjectNode {
        val body = objectMapper.createObjectNode()
        body.put("psave", ps)
        body.put("o", true)
        // Also ask for brightness and bounds, in case the firmware ignores "o" and saves the live state.
        body.put("ib", true)
        body.put("sb", true)
        body.put("n", preset.path("n").asText("").ifBlank { "Boot" })
        body.put("on", preset.path("on").asBoolean(true))
        body.put("bri", preset.path("bri").asInt(state.path("bri").asInt(128)))
        if (preset.has("transition")) body.set<JsonNode>("transition", preset.get("transition"))

        val presetSegs = preset.path("seg").filter { it.isObject && it.path("stop").asInt(1) != 0 }
        val fallbackLook = presetSegs.firstOrNull()
        val segs = body.putArray("seg")
        val ids = mutableSetOf<Int>()
        for (cur in state.path("seg")) {
            val id = cur.path("id").asInt()
            ids += id
            val seg = segs.addObject()
            seg.put("id", id)
            for (k in BOUNDS + LAYOUT) cur.get(k)?.let { seg.set<JsonNode>(k, it) }
            val look = presetSegs.firstOrNull { it.path("id").asInt(-1) == id } ?: fallbackLook
            if (look != null) {
                look.fields().forEach { (k, v) -> if (k !in BOUNDS && k !in LAYOUT && k != "id" && k != "n") seg.set<JsonNode>(k, v) }
            } else {
                seg.put("on", true)
                seg.put("fx", 0)
                seg.set<JsonNode>("col", objectMapper.readTree("[[0,0,0],[0,0,0],[0,0,0]]"))
            }
            cur.get("n")?.let { seg.set<JsonNode>("n", it) }
        }
        for (id in 0 until maxSeg) if (id !in ids) segs.addObject().put("id", id).put("stop", 0)
        return body
    }

    /** Whether [preset]'s live segments have exactly the board's current bounds. */
    internal fun boundsMatch(preset: JsonNode, state: JsonNode): Boolean {
        fun key(n: JsonNode) = Triple(n.path("id").asInt(), n.path("start").asInt(), n.path("stop").asInt())
        val want = state.path("seg").map(::key).toSet()
        val got = preset.path("seg").filter { it.has("start") && it.path("stop").asInt(0) != 0 }.map(::key).toSet()
        return want == got
    }

    /** Puts back the state from before the save, without re-applying a preset. */
    internal fun restoreBody(state: JsonNode): Map<String, Any?> {
        val copy = state.deepCopy<JsonNode>() as ObjectNode
        listOf("ps", "pl", "lor", "udpn", "psave", "nl").forEach { copy.remove(it) }
        @Suppress("UNCHECKED_CAST")
        return objectMapper.convertValue(copy, Map::class.java) as Map<String, Any?>
    }

    private companion object {
        val BOUNDS = listOf("start", "stop", "startY", "stopY", "grp", "spc", "of")
        /** Per-segment wiring flags: always kept from the board, never copied from another segment. */
        val LAYOUT = listOf("rev", "mi", "rY", "mY", "tp", "si", "m12", "sel", "set", "len")
    }
}
