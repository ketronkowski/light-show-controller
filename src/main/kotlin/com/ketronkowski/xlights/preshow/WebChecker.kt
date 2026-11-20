package com.ketronkowski.xlights.preshow

import org.springframework.stereotype.Component
import java.net.URI
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

/**
 * Public web endpoints (Remote Falcon viewer page, live page, ...): answering,
 * expected text present, and TLS certificate not about to expire.
 */
@Component
class WebChecker(
    private val http: JsonHttp,
    private val props: PreShowProperties,
) : PreShowChecker {
    override val layer = "Web"

    override fun check(ctx: CheckContext): List<CheckResult> =
        props.web.flatMap { w -> checkOne(w, ctx.now) }

    private fun checkOne(w: PreShowProperties.WebCheck, now: Instant): List<CheckResult> {
        val bad: (String) -> CheckResult = { msg ->
            if (w.blocker) CheckResult.fail(layer, "Answering", w.name, msg) else CheckResult.warn(layer, "Answering", w.name, msg)
        }
        val out = mutableListOf<CheckResult>()
        out += try {
            val (status, body) = http.getText(w.url)
            when {
                status >= 400 -> bad("answered HTTP $status")
                w.contains.isNotBlank() && status == 200 && !body.contains(w.contains, ignoreCase = true) ->
                    bad("page answered but is missing \"${w.contains}\"")
                else -> CheckResult.pass(layer, "Answering", w.name, "HTTP $status")
            }
        } catch (e: Exception) {
            bad("not answering (${e.brief()})")
        }
        if (w.url.startsWith("https://", ignoreCase = true)) out += certCheck(w, now)
        return out
    }

    private fun certCheck(w: PreShowProperties.WebCheck, now: Instant): List<CheckResult> = try {
        val conn = URI(w.url).toURL().openConnection() as HttpsURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = false
        try {
            conn.connect()
            val leaf = conn.serverCertificates.firstOrNull() as? X509Certificate
            if (leaf == null) emptyList() else {
                val days = Duration.between(now, leaf.notAfter.toInstant()).toDays()
                listOf(
                    if (days < w.minCertDays) CheckResult.warn(layer, "Certificate", w.name, "TLS certificate expires in $days days")
                    else CheckResult.pass(layer, "Certificate", w.name, "valid for $days more days")
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        emptyList() // the answering check already reports connection problems
    }
}
