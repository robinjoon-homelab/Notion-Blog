package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpMediaGatewayTest {
    private val server = MockWebServer()
    private val gateways = mutableListOf<HttpMediaGateway>()

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        gateways.forEach(HttpMediaGateway::close)
        server.shutdown()
    }

    @Test
    fun `stores the received bytes under their SHA-256 key and the unsigned source key`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        server.enqueue(MockResponse().setHeader("Content-Type", "Image/PNG; charset=binary").setBody(Buffer().write(bytes)))
        val source = source("/workspace/file.png?X-Amz-Signature=secret-signature")

        val fetched = gateway().fetch(source)

        assertThat(fetched.bytes).isEqualTo(bytes)
        assertThat(fetched.mediaKey.value).isEqualTo(sha256(bytes))
        assertThat(fetched.contentType).isEqualTo("image/png")
        assertThat(fetched.sourceKey).isEqualTo(SourceMediaKey(server.url("/workspace/file.png").toString()))
    }

    @Test
    fun `requests the signed URL without credentials or cookies`() {
        server.enqueue(MockResponse().setBody("file"))

        gateway().fetch(source("/file.txt?X-Amz-Signature=secret-signature"))

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertThat(request?.method).isEqualTo("GET")
        assertThat(request?.path).isEqualTo("/file.txt?X-Amz-Signature=secret-signature")
        assertThat(request?.getHeader("Authorization")).isNull()
        assertThat(request?.getHeader("Cookie")).isNull()
        assertThat(request?.getHeader("Notion-Version")).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["image/svg+xml", "text/html; charset=utf-8", "application/x-unknown", "not a type", ""])
    fun `stores content types outside the retained list as a download-only octet stream`(contentType: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", contentType).setBody("<svg onload=alert(1)>"))

        val fetched = gateway().fetch(source("/file"))

        assertThat(fetched.contentType).isEqualTo("application/octet-stream")
    }

    @Test
    fun `rejects a declared length above the limit without accepting the body`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(11))))

        assertFetchFails(gateway(maxBytes = 10), "/large?X-Amz-Signature=secret-signature", "byte limit")
    }

    @Test
    fun `rejects a chunked body as soon as it exceeds the limit`() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(64)), 8))

        assertFetchFails(gateway(maxBytes = 20), "/chunked?X-Amz-Signature=secret-signature", "byte limit")
    }

    @Test
    fun `accepts a body exactly at the limit`() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(20) { 7 }), 8))

        assertThat(gateway(maxBytes = 20).fetch(source("/exact")).bytes).hasSize(20)
    }

    @Test
    fun `follows a redirect after checking the target and keeps the original source key`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.png?X-Amz-Signature=second"))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("final"))
        val source = source("/first.png?X-Amz-Signature=first")

        val fetched = gateway().fetch(source)

        assertThat(fetched.bytes).isEqualTo("final".toByteArray())
        assertThat(fetched.sourceKey).isEqualTo(SourceMediaKey.of(source.url))
    }

    @Test
    fun `rejects a redirect chain longer than the limit`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/next")) }
        server.enqueue(MockResponse().setBody("unreachable"))

        assertFetchFails(gateway(maxRedirects = 1), "/start?X-Amz-Signature=secret-signature", "redirect limit")
    }

    @Test
    fun `applies the address policy again to every redirect hop`() {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "http://127.0.0.2:9/metadata"))

        assertFetchFails(gateway(), "/start?X-Amz-Signature=secret-signature", "test origin only")
    }

    @Test
    fun `rejects a redirect without a location`() {
        server.enqueue(MockResponse().setResponseCode(302))

        assertFetchFails(gateway(), "/start?X-Amz-Signature=secret-signature", "no location")
    }

    @ParameterizedTest
    @ValueSource(ints = [203, 206, 304, 400, 403, 404, 429, 500, 503])
    fun `accepts only an HTTP 200 response`(statusCode: Int) {
        server.enqueue(MockResponse().setResponseCode(statusCode).setBody("private-response-body"))

        assertThatThrownBy { gateway().fetch(source("/file?X-Amz-Signature=secret-signature")) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageContaining("HTTP $statusCode")
            .hasMessageNotContaining("secret-signature")
            .hasMessageNotContaining("private-response-body")
    }

    @Test
    fun `rejects an encoded body whose bytes are not the stored file`() {
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody("compressed"))

        assertFetchFails(gateway(), "/file?X-Amz-Signature=secret-signature", "encoded body")
    }

    @Test
    fun `rejects an empty body`() {
        server.enqueue(MockResponse().setBody(""))

        assertFetchFails(gateway(), "/empty?X-Amz-Signature=secret-signature", "no bytes")
    }

    @Test
    fun `fails when the response headers do not arrive before the deadline`() {
        server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody("late"))

        assertThatThrownBy { gateway(timeout = Duration.ofMillis(200)).fetch(source("/slow?X-Amz-Signature=secret-signature")) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageMatching(".*(timed out|deadline).*")
            .hasMessageNotContaining("secret-signature")
    }

    @Test
    fun `fails when a slowly streamed body exceeds the whole download deadline`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(40))).throttleBody(4, 100, TimeUnit.MILLISECONDS))

        assertFetchFails(gateway(timeout = Duration.ofMillis(300)), "/trickle?X-Amz-Signature=secret-signature", "deadline")
    }

    @Test
    fun `fails when the address check does not finish before the deadline`() {
        val release = CountDownLatch(1)
        val stalled = MediaAddressPolicy { release.await() }
        val gateway = HttpMediaGateway(MediaFetchLimits(1024, Duration.ofMillis(200), 3), stalled).also(gateways::add)
        try {
            assertThatThrownBy { gateway.fetch(source("/file?X-Amz-Signature=secret-signature")) }
                .isInstanceOf(MediaFetchException::class.java)
                .hasMessageContaining("deadline")
                .hasMessageNotContaining("secret-signature")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `fails when the connection closes during the body`() {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(4096)))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        assertFetchFails(gateway(maxBytes = 8192), "/broken?X-Amz-Signature=secret-signature", "could not be completed")
    }

    @Test
    fun `keeps the interruption cause and the interrupted status of the caller`() {
        server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody("late"))
        val gateway = gateway()
        Thread.currentThread().interrupt()
        try {
            assertThatThrownBy { gateway.fetch(source("/file?X-Amz-Signature=secret-signature")) }
                .isInstanceOf(MediaFetchException::class.java)
                .hasRootCauseInstanceOf(InterruptedException::class.java)
                .hasMessageNotContaining("secret-signature")
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `production defaults refuse a plain HTTP loopback URL`() {
        val gateway = HttpMediaGateway(MediaFetchLimits(1024, Duration.ofSeconds(1), 3)).also(gateways::add)

        assertThatThrownBy { gateway.fetch(source("/internal?X-Amz-Signature=secret-signature")) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageContaining("HTTPS")
            .hasMessageNotContaining("secret-signature")
    }

    @Test
    fun `never follows a redirect from HTTPS to plain HTTP`() {
        val redirect = mockk<HttpResponse<MediaResponseBody>>()
        every { redirect.statusCode() } returns 302
        every { redirect.headers() } returns HttpHeaders.of(mapOf("Location" to listOf("http://files.example/next"))) { _, _ -> true }
        every { redirect.body() } returns MediaResponseBody.Skipped
        val client = mockk<HttpClient>()
        every { client.sendAsync(any(), any<HttpResponse.BodyHandler<MediaResponseBody>>()) } returns
            CompletableFuture.completedFuture(redirect)
        every { client.close() } just Runs
        val gateway = HttpMediaGateway(MediaFetchLimits(1024, Duration.ofSeconds(1), 3), { }, client).also(gateways::add)

        val source = MediaSource.SourceHosted(URI("https://files.example/a?X-Amz-Signature=secret-signature"), null)

        assertThatThrownBy { gateway.fetch(source) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageContaining("stay on HTTPS")
            .hasMessageNotContaining("secret-signature")
    }

    private fun assertFetchFails(
        gateway: HttpMediaGateway,
        path: String,
        message: String,
    ) {
        assertThatThrownBy { gateway.fetch(source(path)) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageContaining(message)
            .hasMessageNotContaining("secret-signature")
    }

    private fun gateway(
        maxBytes: Long = 1024,
        maxRedirects: Int = 3,
        timeout: Duration = Duration.ofSeconds(2),
    ): HttpMediaGateway = HttpMediaGateway(MediaFetchLimits(maxBytes, timeout, maxRedirects), testOriginOnly()).also(gateways::add)

    // MockWebServer는 평문 loopback이므로 운영 정책 대신 이 서버의 원점만 허용하는 정책을 주입한다.
    private fun testOriginOnly(): MediaAddressPolicy =
        MediaAddressPolicy { url ->
            if (url.scheme != "http" || url.host != server.hostName || url.port != server.port) {
                throw MediaFetchException("test origin only")
            }
        }

    private fun source(path: String): MediaSource.SourceHosted = MediaSource.SourceHosted(URI(server.url(path).toString()), null)

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
