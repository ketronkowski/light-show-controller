package com.ketronkowski.xlights.lighttest

import com.ketronkowski.xlights.preshow.PreShowProperties
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class ProtectCameraTest {

    /** Like the UniFi console: a self-signed certificate for unifi.local, reached by IP address. */
    @Test
    fun `snapshot works against a self-signed certificate that doesn't name the IP address`(@TempDir dir: Path) {
        val ks = dir.resolve("ks.p12").toFile()
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "c", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=unifi.local", "-ext", "SAN=dns:unifi.local,dns:localhost", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "secret", "-keypass", "secret")
            .redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readAllBytes().decodeToString() }

        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "secret".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "secret".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val jpeg = byteArrayOf(1, 2, 3)
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ssl)
            createContext("/") { ex -> ex.sendResponseHeaders(200, jpeg.size.toLong()); ex.responseBody.use { it.write(jpeg) } }
            start()
        }
        try {
            val props = PreShowProperties(lights = PreShowProperties.LightTest(
                protectUrl = "https://127.0.0.1:${server.address.port}", protectApiKey = "k", cameraId = "c"))
            assertArrayEquals(jpeg, ProtectCamera(props).snapshot())
        } finally {
            server.stop(0)
        }
    }
}
