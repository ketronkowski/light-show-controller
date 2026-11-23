package com.ketronkowski.xlights.preshow

import org.springframework.stereotype.Component

/**
 * Live stream: is the iPhone feed live, is the stream using it or a fallback camera,
 * and does the stream have audio? Read from Home Assistant, which polls the stream
 * box's status endpoint. These are warnings, not blockers: the lights show goes on
 * without the stream, and the video falls back to another camera on its own.
 */
@Component
class CameraChecker(
    private val ha: HomeAssistantClient,
    private val props: PreShowProperties,
) : PreShowChecker {
    override val layer = "Camera"
    private val cfg get() = props.ha

    override fun check(ctx: CheckContext): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        if (cfg.phoneFeedEntity.isNotBlank()) results.add(phoneCheck())
        if (cfg.streamAudioEntity.isNotBlank()) results.add(audioCheck())
        return results
    }

    private fun phoneCheck(): CheckResult {
        if (!ha.configured) return CheckResult.skip(layer, "iPhone feed", "iPhone", "Home Assistant is not configured")
        val phone = try {
            ha.state(cfg.phoneFeedEntity).path("state").asText("unknown")
        } catch (e: Exception) {
            return CheckResult.warn(layer, "iPhone feed", "iPhone", "feed status not readable (${e.brief()})")
        }
        return feedResult(phone, optionalState(cfg.feedSourceEntity))
    }

    private fun audioCheck(): CheckResult {
        if (!ha.configured) return CheckResult.skip(layer, "Stream audio", "Audio", "Home Assistant is not configured")
        val ok = try {
            ha.state(cfg.streamAudioEntity).path("state").asText("unknown")
        } catch (e: Exception) {
            return CheckResult.warn(layer, "Stream audio", "Audio", "audio status not readable (${e.brief()})")
        }
        return audioResult(ok, optionalState(cfg.streamAudioPeakEntity)?.toDoubleOrNull())
    }

    private fun optionalState(entity: String): String? = if (entity.isBlank()) null else try {
        ha.state(entity).path("state").asText("").ifBlank { null }
    } catch (_: Exception) {
        null
    }

    /**
     * [ok] is the audio_ok sensor state. Silence alone is not judged here: before the
     * show FPP is usually idle, so a quiet input is normal. A missing device or track is.
     */
    internal fun audioResult(ok: String, peakDb: Double?): CheckResult {
        val peak = peakDb?.let { ", peak %.0f dBFS".format(it) } ?: ""
        return when (ok) {
            "on" -> CheckResult.pass(layer, "Stream audio", "Audio", "audio input is live$peak")
            "off" -> CheckResult.warn(layer, "Stream audio", "Audio",
                "the stream has no audio$peak. Check the ClearClick USB audio adapter on live-01 " +
                    "(replug it if it dropped off USB) and the FPP audio lead.")
            else -> CheckResult.warn(layer, "Stream audio", "Audio", "audio status is $ok: the stream box may be unreachable")
        }
    }

    internal fun feedResult(phone: String, source: String?): CheckResult {
        val usingPhone = source?.equals(cfg.phoneSourceName, ignoreCase = true)
        return when (phone) {
            "on" -> when (usingPhone) {
                false -> CheckResult.warn(layer, "iPhone feed", "iPhone", "iPhone feed is live, but the stream is still on $source")
                else -> CheckResult.pass(layer, "iPhone feed", "iPhone", "live" + if (usingPhone == true) ", stream is using it" else "")
            }
            "off" -> CheckResult.warn(layer, "iPhone feed", "iPhone",
                "iPhone feed is not live" + (source?.let { ", stream is on $it" } ?: "") +
                    ". Open Blackmagic Camera and toggle Live Stream off and on (it can show On Air after a server restart).")
            else -> CheckResult.warn(layer, "iPhone feed", "iPhone", "feed status is $phone: the stream box may be unreachable")
        }
    }
}
