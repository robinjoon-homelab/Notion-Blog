package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Instant

internal class MediaInspectionFixture(
    private val directory: Path,
) : AutoCloseable {
    val ca: HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName("throwaway inspection CA")
            .certificateAuthority(0)
            .build()
    private val servers = mutableListOf<MockWebServer>()

    fun certificate(host: String = "first.media.test"): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(host)
            .addSubjectAlternativeName(host)
            .signedBy(ca)
            .build()

    fun expiredCertificate(): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName("first.media.test")
            .addSubjectAlternativeName("first.media.test")
            .validityInterval(Instant.now().minusSeconds(7200).toEpochMilli(), Instant.now().minusSeconds(3600).toEpochMilli())
            .signedBy(ca)
            .build()

    fun server(certificate: HeldCertificate = certificate()): MockWebServer {
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate, ca.certificate).build()
        val server = MockWebServer()
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.useHttps(tls.sslSocketFactory(), false)
        server.start()
        servers.add(server)
        return server
    }

    fun tunnel(proxy: MockWebServer) {
        proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END))
    }

    fun settings(proxy: MockWebServer): Map<String, String> =
        trustSettings() +
            mapOf(
                "https.proxyHost" to "localhost",
                "https.proxyPort" to proxy.port.toString(),
                "http.nonProxyHosts" to "localhost|127.*|[::1]|*.svc|*.svc.cluster.local|*.cluster.local|10.42.*|10.43.*",
            )

    fun trustSettings(trustedCa: HeldCertificate = ca): Map<String, String> {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setCertificateEntry("inspection", trustedCa.certificate)
        val path = directory.resolve("inspection.p12")
        Files.newOutputStream(path).use { store.store(it, "changeit".toCharArray()) }
        return mapOf(
            "javax.net.ssl.trustStore" to path.toString(),
            "javax.net.ssl.trustStoreType" to "PKCS12",
            "javax.net.ssl.trustStorePassword" to "changeit",
        )
    }

    override fun close() {
        servers.forEach(MockWebServer::shutdown)
    }
}
