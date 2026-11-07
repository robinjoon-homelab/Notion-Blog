package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HttpMediaGatewayProxyTest(
    @TempDir directory: Path,
) {
    private val fixture = MediaInspectionFixture(directory)
    private val jvm = MediaProxyJvm(directory)

    @AfterEach
    fun closeServers() {
        fixture.close()
    }

    @Test
    fun `uses JVM HTTPS CONNECT and trusts the inspection CA without ambient credentials`() {
        val proxy = fixture.server()
        fixture.tunnel(proxy)
        proxy.enqueue(MockResponse().setBody("file"))

        probe(fixture.settings(proxy))

        assertConnect(proxy, "first.media.test:443")
        assertMediaRequest(proxy, "/file?signature=secret-signature")
    }

    @Test
    fun `rejects an inspection certificate signed by an untrusted CA`() {
        val proxy = fixture.server()
        fixture.tunnel(proxy)
        proxy.enqueue(MockResponse().setBody("file"))
        val unrelatedCa =
            HeldCertificate
                .Builder()
                .commonName("untrusted CA")
                .certificateAuthority(0)
                .build()
        val settings = fixture.settings(proxy) + fixture.trustSettings(unrelatedCa)

        probe(settings, expected = "fail:SSLHandshakeException")

        assertConnect(proxy, "first.media.test:443")
        assertThat(proxy.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `rejects a trusted certificate for the wrong hostname`() {
        val proxy = fixture.server(fixture.certificate("wrong.media.test"))
        fixture.tunnel(proxy)
        proxy.enqueue(MockResponse().setBody("file"))

        probe(fixture.settings(proxy), expected = "fail:SSLHandshakeException")

        assertConnect(proxy, "first.media.test:443")
        assertThat(proxy.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `rejects an expired inspection certificate`() {
        val proxy = fixture.server(fixture.expiredCertificate())
        fixture.tunnel(proxy)
        proxy.enqueue(MockResponse().setBody("file"))

        probe(fixture.settings(proxy), expected = "fail:SSLHandshakeException")

        assertConnect(proxy, "first.media.test:443")
        assertThat(proxy.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["localhost", "127.0.0.1"])
    fun `standard nonProxyHosts keeps local HTTPS direct`(host: String) {
        val direct = fixture.server(fixture.certificate(host))
        val proxy = fixture.server()
        direct.enqueue(MockResponse().setBody("file"))

        probe(fixture.settings(proxy), "https://$host:${direct.port}/file")

        assertMediaRequest(direct, "/file")
        assertThat(proxy.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `keeps HTTPS direct when no JVM proxy is configured`() {
        val direct = fixture.server(fixture.certificate("localhost"))
        direct.enqueue(MockResponse().setBody("file"))

        probe(fixture.trustSettings(), "https://localhost:${direct.port}/file")

        assertMediaRequest(direct, "/file")
    }

    @Test
    fun `proxy failure does not fall back to a reachable direct origin or ambient authentication`() {
        val direct = fixture.server(fixture.certificate("localhost"))
        val proxy = fixture.server()
        // A rejected CONNECT cannot reach the TLS origin even when it is reachable locally.
        proxy.enqueue(
            MockResponse()
                .setResponseCode(407)
                .setHeader("Proxy-Authenticate", "Basic realm=test")
                .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END),
        )
        val settings = fixture.settings(proxy) + ("http.nonProxyHosts" to "")

        probe(settings, "https://localhost:${direct.port}/file", "fail:HTTP 407")

        assertConnect(proxy, "localhost:${direct.port}")
        assertThat(proxy.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()
        assertThat(direct.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `Notion uses JVM inspection trust and its bearer token stays isolated from redirected media`() {
        val cert =
            HeldCertificate
                .Builder()
                .commonName("inspection")
                .addSubjectAlternativeName("api.notion.com")
                .addSubjectAlternativeName("first.media.test")
                .addSubjectAlternativeName("second.media.test")
                .signedBy(fixture.ca)
                .build()
        val proxy = fixture.server(cert)
        fixture.tunnel(proxy)
        proxy.enqueue(MockResponse().setBody("{\"ok\":true}").setHeader("Content-Type", "application/json"))
        fixture.tunnel(proxy)
        proxy.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Set-Cookie", "session=redirect-secret; Domain=.media.test; Secure")
                .setHeader("Location", "https://second.media.test/final"),
        )
        fixture.tunnel(proxy)
        proxy.enqueue(MockResponse().setBody("file"))

        jvm.run(fixture.settings(proxy), listOf(source, "ok:file", "5000", "1024", "notion"))

        assertConnect(proxy, "api.notion.com:443")
        val notion = requireNotNull(proxy.takeRequest(1, TimeUnit.SECONDS))
        assertThat(notion.getHeader("Authorization")).isEqualTo("Bearer test-notion-secret")
        assertConnect(proxy, "first.media.test:443")
        assertMediaRequest(proxy, "/file?signature=secret-signature")
        assertConnect(proxy, "second.media.test:443")
        assertMediaRequest(proxy, "/final")
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://private.media.test/metadata", "https://unknown.media.test/file", "http://second.media.test/file"])
    fun `validates every redirect destination before sending it to the proxy`(target: String) {
        val proxy = fixture.server()
        fixture.tunnel(proxy)
        proxy.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
        val failure =
            if (target.startsWith("http:")) {
                "stay on HTTPS"
            } else if (target.contains("private")) {
                "non-public"
            } else {
                "not an allowed"
            }

        probe(fixture.settings(proxy), expected = "fail:$failure")

        assertConnect(proxy, "first.media.test:443")
        assertMediaRequest(proxy, "/file?signature=secret-signature")
        assertThat(proxy.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `retains response size limits through inspection`() {
        val proxy = fixture.server()
        fixture.tunnel(proxy)
        proxy.enqueue(MockResponse().setChunkedBody("x".repeat(2048), 64))

        probe(fixture.settings(proxy), expected = "fail:byte limit")

        assertConnect(proxy, "first.media.test:443")
        assertMediaRequest(proxy, "/file?signature=secret-signature")
    }

    @ParameterizedTest
    @ValueSource(strings = ["headers", "body"])
    fun `retains the whole request deadline through inspection`(delay: String) {
        val proxy = fixture.server()
        fixture.tunnel(proxy)
        val response = MockResponse().setBody("x".repeat(40))
        if (delay == "headers") response.setHeadersDelay(3, TimeUnit.SECONDS) else response.throttleBody(1, 200, TimeUnit.MILLISECONDS)
        proxy.enqueue(response)

        jvm.run(fixture.settings(proxy), listOf(source, "fail:TimeoutException", "1500", "1024", "media"))

        assertConnect(proxy, "first.media.test:443")
        assertMediaRequest(proxy, "/file?signature=secret-signature")
    }

    private fun probe(
        settings: Map<String, String>,
        url: String = source,
        expected: String = "ok:file",
    ) {
        jvm.run(settings, listOf(url, expected, "5000", "1024", "media"))
    }

    private fun assertConnect(
        proxy: MockWebServer,
        authority: String,
    ) {
        val request = requireNotNull(proxy.takeRequest(1, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("CONNECT")
        assertThat(request.requestLine).contains(authority)
        assertNoCredentials(request)
    }

    private fun assertMediaRequest(
        server: MockWebServer,
        path: String,
    ) {
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo(path)
        assertNoCredentials(request)
    }

    private fun assertNoCredentials(request: RecordedRequest) {
        assertThat(request.getHeader("Authorization")).isNull()
        assertThat(request.getHeader("Proxy-Authorization")).isNull()
        assertThat(request.getHeader("Cookie")).isNull()
        assertThat(request.getHeader("Notion-Version")).isNull()
    }

    private companion object {
        val source = "https://first.media.test/file?signature=secret-signature"
    }
}
