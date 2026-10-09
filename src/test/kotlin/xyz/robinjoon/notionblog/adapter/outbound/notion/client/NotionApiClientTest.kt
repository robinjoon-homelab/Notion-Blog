package xyz.robinjoon.notionblog.adapter.outbound.notion.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import java.time.Duration

class NotionApiClientTest {
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
    fun `fetches a page with the fixed Notion version and returns its internal envelope`() {
        server.enqueueJson(
            """
            {
              "id": "page-1",
              "parent": {"type": "workspace", "workspace": true},
              "url": "https://www.notion.so/page-1",
              "public_url": "https://workspace.notion.site/page-1",
              "in_trash": false,
              "last_edited_time": "2026-08-25T00:00:00Z",
              "properties": {"Name": {"type": "title", "title": []}}
            }
            """,
        )

        val page = client().fetchPage("page-1")

        assertThat(page.id).isEqualTo("page-1")
        assertThat(page.parent.type).isEqualTo("workspace")
        assertThat(page.publicUrl).isEqualTo("https://workspace.notion.site/page-1")
        assertThat(
            page.properties
                .path("Name")
                .path("type")
                .asString(),
        ).isEqualTo("title")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/v1/pages/page-1")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token")
        assertThat(request.getHeader("Notion-Version")).isEqualTo("2026-03-11")
    }

    @Test
    fun `collects all direct block-child cursor pages without recursing into child blocks`() {
        server.enqueueJson(
            """
            {
              "results": [{
                "id": "block-1",
                "type": "paragraph",
                "has_children": true,
                "in_trash": false,
                "paragraph": {"rich_text": [{"plain_text": "First"}]}
              }],
              "has_more": true,
              "next_cursor": "next-page"
            }
            """,
        )
        server.enqueueJson(
            """
            {
              "results": [{
                "id": "block-2",
                "type": "unsupported_type",
                "has_children": false,
                "in_trash": false,
                "unsupported_type": {"example": true}
              }],
              "has_more": false,
              "next_cursor": null
            }
            """,
        )

        val blocks = client().fetchDirectBlockChildren("parent-block")

        assertThat(blocks).extracting<String> { it.id }.containsExactly("block-1", "block-2")
        assertThat(
            blocks
                .first()
                .payload
                .path("rich_text")
                .first()
                .path("plain_text")
                .asString(),
        ).isEqualTo("First")
        assertThat(blocks.first().hasChildren).isTrue()
        assertThat(blocks.first().inTrash).isFalse()

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.path).isEqualTo("/v1/blocks/parent-block/children?page_size=100")
        assertThat(second.path).isEqualTo("/v1/blocks/parent-block/children?page_size=100&start_cursor=next-page")
        assertThat(first.getHeader("Notion-Version")).isEqualTo("2026-03-11")
        assertThat(second.getHeader("Notion-Version")).isEqualTo("2026-03-11")
    }

    @Test
    fun `collects all settings cursor pages with a page size of one hundred`() {
        server.enqueueJson(
            """
            {
              "results": [{"id": "settings-1", "properties": {"Key": {"type": "title", "title": []}}}],
              "has_more": true,
              "next_cursor": "cursor-2"
            }
            """,
        )
        server.enqueueJson(
            """
            {
              "results": [{"id": "settings-2", "properties": {"Key": {"type": "title", "title": []}}}],
              "has_more": false,
              "next_cursor": null
            }
            """,
        )

        val rows = client().fetchSettingsRows("settings-source")

        assertThat(rows).extracting<String> { it.id }.containsExactly("settings-1", "settings-2")
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.path).isEqualTo("/v1/data_sources/settings-source/query")
        assertThat(first.body.readUtf8()).contains("\"page_size\":100").doesNotContain("start_cursor")
        assertThat(second.body.readUtf8()).contains("\"page_size\":100", "\"start_cursor\":\"cursor-2\"")
        assertThat(first.getHeader("Notion-Version")).isEqualTo("2026-03-11")
    }

    @Test
    fun `rejects an inconsistent pagination response without exposing request secrets or response data`() {
        val sensitiveResponse = "token=server-secret"
        server.enqueueJson(
            """
            {"results": [], "has_more": false, "next_cursor": "$sensitiveResponse"}
            """,
        )

        assertThatThrownBy { client(token = "client-secret").fetchSettingsRows("settings-source") }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasMessageNotContaining("client-secret")
            .hasMessageNotContaining(sensitiveResponse)
            .hasMessageNotContaining(server.url("/").toString())
    }

    @Test
    fun `translates a missing Notion object to source access without exposing the URL or body`() {
        val sensitiveResponse = "upstream-secret"
        server.enqueue(MockResponse().setResponseCode(404).setBody(sensitiveResponse))

        assertThatThrownBy { client(token = "client-secret").fetchPage("missing-page") }
            .isInstanceOf(SourceAccessException::class.java)
            .hasMessageNotContaining("client-secret")
            .hasMessageNotContaining(sensitiveResponse)
            .hasMessageNotContaining(server.url("/").toString())
    }

    @Test
    fun `fails collection before issuing requests after its total deadline has elapsed`() {
        assertThatThrownBy {
            client(collectionTimeout = Duration.ofNanos(1)).fetchDirectBlockChildren("parent-block")
        }.isInstanceOf(RetryableSourceException::class.java)

        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `accepts only the official HTTPS origin or a loopback origin for tests`() {
        listOf(
            "http://api.notion.com/v1",
            "https://api.notion.com:8443/v1",
            "https://user@api.notion.com/v1",
            "https://api.notion.com.evil.example/v1",
            "https://example.com/v1",
        ).forEach { baseUrl ->
            assertThatIllegalArgumentException().isThrownBy {
                NotionApiClient(baseUrl, "test-token", Duration.ofSeconds(1), Duration.ofSeconds(2))
            }
        }

        NotionApiClient("https://api.notion.com/v1", "test-token", Duration.ofSeconds(1), Duration.ofSeconds(2))
    }

    private fun client(
        token: String = "test-token",
        collectionTimeout: Duration = Duration.ofSeconds(2),
    ): NotionApiClient =
        NotionApiClient(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            token = token,
            requestTimeout = Duration.ofSeconds(1),
            collectionTimeout = collectionTimeout,
        )

    private fun MockWebServer.enqueueJson(body: String) {
        enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.trimIndent()))
    }
}
