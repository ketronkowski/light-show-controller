package com.ketronkowski.xlights.lighttest

import com.ketronkowski.xlights.preshow.PreShowProperties
import org.springframework.stereotype.Component
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** Something that hands back a JPEG of the yard. */
fun interface Camera {
    fun snapshot(): ByteArray
}

/**
 * The show camera through UniFi Protect's integration API:
 * `GET <console>/proxy/protect/integration/v1/cameras/<id>/snapshot?highQuality=true`.
 * Protect caches a snapshot for a fraction of a second, so callers wait between pictures.
 */
@Component
class ProtectCamera(private val props: PreShowProperties) : Camera {
    private val cfg get() = props.lights

    private val client: HttpClient by lazy {
        val b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
        if (cfg.insecureTls) {
            // The console serves a self-signed certificate for unifi.local, not its LAN address.
            // An *extended* trust manager is needed: Java checks the host name itself around a
            // plain X509TrustManager, and HttpClient asks for that check whatever SSLParameters say.
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(TrustAll), SecureRandom()) }
            b.sslContext(ctx).sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
        }
        b.build()
    }

    override fun snapshot(): ByteArray {
        check(cfg.configured) { "the camera is not configured (preshow.lights.protect-url, protect-api-key, camera-id)" }
        val url = "${cfg.protectUrl.trimEnd('/')}/proxy/protect/integration/v1/cameras/${cfg.cameraId}/snapshot?highQuality=true"
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("X-API-KEY", cfg.protectApiKey)
            .header("Accept", "image/jpeg")
            .timeout(Duration.ofSeconds(10))
            .GET().build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        check(res.statusCode() == 200) { "camera snapshot answered HTTP ${res.statusCode()}" }
        return res.body()
    }

    /** Accepts any certificate for any host name. Only for the console on the LAN. */
    internal object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
