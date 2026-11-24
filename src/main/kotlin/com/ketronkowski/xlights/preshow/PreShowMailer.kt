package com.ketronkowski.xlights.preshow

import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/** Emails the HTML report through SMTP (Gmail with an app password, like the homelab report). */
@Component
class PreShowMailer(
    private val props: PreShowProperties,
    private val renderer: ReportRenderer,
    private val store: PreShowStore,
) {
    private val log = LoggerFactory.getLogger(PreShowMailer::class.java)

    val configured: Boolean get() = props.email.configured

    fun send(run: PreShowRun): Boolean {
        val e = props.email
        if (!e.configured) return false
        return runCatching {
            val sender = JavaMailSenderImpl().apply {
                host = e.host
                port = e.port
                username = e.username.ifBlank { e.from }
                password = e.password
                javaMailProperties["mail.smtp.auth"] = "true"
                javaMailProperties["mail.smtp.starttls.enable"] = "true"
                javaMailProperties["mail.smtp.starttls.required"] = "true"
                javaMailProperties["mail.smtp.connectiontimeout"] = "10000"
                javaMailProperties["mail.smtp.timeout"] = "20000"
            }
            val msg = sender.createMimeMessage()
            val image = store.attachment(run.id, "lights.jpg")
            MimeMessageHelper(msg, image != null, "UTF-8").apply {
                setFrom(e.from)
                setTo(e.to.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
                setSubject(renderer.subject(run))
                setText(renderer.render(run, image?.let { "cid:lights" }), true)
                if (image != null) addInline("lights", org.springframework.core.io.ByteArrayResource(image), "image/jpeg")
            }
            sender.send(msg)
            log.info("Pre-show report {} emailed", run.id)
            true
        }.onFailure { log.warn("Pre-show report email failed: {}", it.brief()) }.getOrDefault(false)
    }
}
