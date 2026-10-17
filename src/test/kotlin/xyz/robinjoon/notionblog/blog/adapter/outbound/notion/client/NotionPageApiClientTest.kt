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
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import java.time.Duration

class NotionPageApiClientTest {
    private val server = MockWebServer()

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `requests only visible row properties and retains the data source parent`() {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/row-1","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{}}
            """,
        )

        val row = client().fetchPage("row-1", listOf("title", "f%5C%5C"))

        assertThat(row.parent.dataSourceId).isEqualTo("source-1")
        assertThat(row.publicUrl).isEqualTo("https://workspace.notion.site/row-1")
        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameterValues("filter_properties[]")).containsExactly("title", "f\\\\")
        assertThat(request.requestUrl?.queryParameterNames).containsExactly("filter_properties[]")
    }

    @Test
    fun `transmits encoded property identifiers once while preserving literal plus and percent characters`() {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/row-1","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{}}
            """,
        )

        client().fetchPage("row-1", listOf("title", "A%5CrS", "b%3CD%5D", "a%2Bb", "a+b", "a%252F"))

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/v1/pages/row-1")
        assertThat(request.requestUrl?.encodedQuery).isEqualTo(
            "filter_properties%5B%5D=title&filter_properties%5B%5D=A%5CrS&filter_properties%5B%5D=b%3CD%5D" +
                "&filter_properties%5B%5D=a%2Bb&filter_properties%5B%5D=a%2Bb&filter_properties%5B%5D=a%252F",
        )
        assertThat(request.requestUrl?.queryParameterValues("filter_properties[]"))
            .containsExactly("title", "A\\rS", "b<D]", "a+b", "a+b", "a%2F")
    }

    @Test
    fun `retains optional page icon and cover media payload within the Notion adapter`() {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/row-1","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{},
             "icon":{"type":"emoji","emoji":"📚"},
             "cover":{"type":"file","file":{"url":"https://example.com/cover.jpg","expiry_time":"2026-09-01T00:00:00Z"}}}
            """,
        )

        val row = client().fetchPage("row-1")

        assertThat(row.icon?.get("emoji")?.stringValue()).isEqualTo("📚")
        assertThat(
            row.cover
                ?.get("file")
                ?.get("expiry_time")
                ?.stringValue(),
        ).isEqualTo("2026-09-01T00:00:00Z")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ",\"icon\":null,\"cover\":null"])
    fun `allows absent and cleared page media without inventing a cover`(media: String) {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/row-1","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{}$media}
            """,
        )

        val row = client().fetchPage("row-1")

        assertThat(row.icon).isNull()
        assertThat(row.cover).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"icon\":1", "\"cover\":false"])
    fun `rejects malformed page media envelope without exposing its contents`(media: String) {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/source-private-label","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{},$media}
            """,
        )

        assertThatThrownBy { client().fetchPage("row-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasMessageNotContaining("source-private-label")
    }

    @Test
    fun `returns one direct block page with its continuation cursor`() {
        enqueueJson(
            """
            {"object":"list","results":[
              {"object":"block","id":"image-1","type":"image","has_children":false,"in_trash":false,
               "image":{"type":"external","external":{"url":"https://example.com/cover.jpg"},"caption":[]}}],
             "has_more":true,"next_cursor":"next-image-cursor"}
            """,
        )

        val page = client().fetchBlockChildrenPage("row-1", "image-cursor")

        assertThat(page.results.map { it.id }).containsExactly("image-1")
        assertThat(page.results.single().type).isEqualTo("image")
        assertThat(page.hasMore).isTrue()
        assertThat(page.nextCursor).isEqualTo("next-image-cursor")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/v1/blocks/row-1/children")
        assertThat(request.requestUrl?.queryParameter("page_size")).isEqualTo("100")
        assertThat(request.requestUrl?.queryParameter("start_cursor")).isEqualTo("image-cursor")
    }

    private fun client(): NotionApiClient =
        NotionApiClient(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            token = "test-token",
            requestTimeout = Duration.ofSeconds(1),
            collectionTimeout = Duration.ofSeconds(2),
        )

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.trimIndent()))
    }
}
