package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class NotionApiFailureTest {
    private val server = MockWebServer()

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 429, 503])
    fun `retains the HTTP failure cause behind a safe source message`(statusCode: Int) {
        server.enqueue(MockResponse().setResponseCode(statusCode).setBody("private-response-body"))

        assertThatThrownBy { client().fetchPage("private-page") }
            .isInstanceOf(SourceException::class.java)
            .hasCauseInstanceOf(RestClientResponseException::class.java)
            .hasMessageNotContaining("private-response-body")
            .hasMessageNotContaining("private-page")
            .hasMessageNotContaining("test-token")
            .isInstanceOfSatisfying(SourceException::class.java) { failure ->
                assertThat(failure.diagnostic?.stage).isEqualTo(MigrationFailureStage.SOURCE)
                assertThat(failure.diagnostic?.httpStatus).isEqualTo(statusCode)
                assertThat(failure.diagnostic.toString()).doesNotContain("private-response-body", "private-page", "test-token")
            }
    }

    @Test
    fun `retains the schema validation cause for an invalid page response`() {
        enqueueJson("""{"id":"private-page"}""")

        assertThatThrownBy { client().fetchPage("private-page") }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
            .hasMessageNotContaining("private-page")
    }

    @Test
    fun `retains the pagination validation cause for an inconsistent cursor`() {
        enqueueJson("""{"results":[],"has_more":true,"next_cursor":null}""")

        assertThatThrownBy { client().fetchSettingsRows("private-source") }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
            .hasMessageNotContaining("private-source")
    }

    @Test
    fun `retains the HTTP conversion cause for malformed JSON`() {
        enqueueJson("{\"private-response-body\":")

        assertThatThrownBy { client().fetchPage("private-page") }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasCauseInstanceOf(RestClientException::class.java)
            .hasMessageNotContaining("private-response-body")
    }

    @Test
    fun `retains the timeout cause as a retryable source failure`() {
        server.enqueue(MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody("{}"))

        assertThatThrownBy { client(Duration.ofMillis(100)).fetchPage("private-page") }
            .isInstanceOf(RetryableSourceException::class.java)
            .hasCauseInstanceOf(ResourceAccessException::class.java)
            .hasMessageNotContaining("private-page")
    }

    @Test
    fun `retains the interruption cause and interrupted status of the caller`() {
        val client = client()
        Thread.currentThread().interrupt()
        try {
            assertThatThrownBy { client.fetchPage("private-page") }
                .isInstanceOf(RetryableSourceException::class.java)
                .hasCauseInstanceOf(ResourceAccessException::class.java)
                .hasRootCauseInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `request diagnostics identify a wrapped TLS failure without exposing its cause message`() {
        val tls = SSLHandshakeException("private-host")
        tls.initCause(IllegalArgumentException("private-certificate"))

        val failure = NotionFailureTranslator().requestFailure(IOException("private-url", tls))

        assertThat(failure.diagnostic?.reason).contains("보안 연결")
        assertThat(failure.diagnostic.toString()).doesNotContain("private-host", "private-certificate", "private-url")
        assertThat(failure.cause).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `request diagnostics stop traversing a cyclic cause chain`() {
        val first = IOException("private-first")
        val second = IOException("private-second", first)
        first.initCause(second)

        val failure = NotionFailureTranslator().requestFailure(first)

        assertThat(failure.diagnostic?.stage).isEqualTo(MigrationFailureStage.SOURCE)
        assertThat(failure.diagnostic?.reason).contains("통신")
        assertThat(failure.diagnostic.toString()).doesNotContain("private-first", "private-second")
    }

    @Test
    fun `unexpected HTTP status preserves the retryable classification without invalid diagnostic metadata`() {
        val failure = NotionFailureTranslator().httpFailure(700)

        assertThat(failure).isInstanceOf(RetryableSourceException::class.java)
        assertThat(failure.diagnostic?.httpStatus).isNull()
        assertThat(failure.message).contains("HTTP 700")
    }

    private fun client(requestTimeout: Duration = Duration.ofSeconds(1)): NotionApiClient =
        NotionApiClient(server.url("/v1").toString(), "test-token", requestTimeout, Duration.ofSeconds(2))

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }
}
