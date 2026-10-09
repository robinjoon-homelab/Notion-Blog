package xyz.robinjoon.notionblog.adapter.outbound.notion.client

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
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceException
import java.time.Duration
import java.util.concurrent.TimeUnit

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

    private fun client(requestTimeout: Duration = Duration.ofSeconds(1)): NotionApiClient =
        NotionApiClient(server.url("/v1").toString(), "test-token", requestTimeout, Duration.ofSeconds(2))

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }
}
