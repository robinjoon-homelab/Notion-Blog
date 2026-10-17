package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import java.time.Duration

class NotionFailurePreservationTest {
    private val server = MockWebServer()
    private val reference = SourceDocumentRef(SourceId("notion-main"), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @ParameterizedTest
    @ValueSource(strings = ["42", "false", "{}", "[]", "\"\"", "\" \""])
    fun `a malformed public_url hint is ignored and the page still imports`(publicUrl: String) {
        enqueuePage(publicUrl)
        enqueueChildren()

        assertThat(source().fetch(reference).title).isEqualTo("Incoming title")
    }

    @ParameterizedTest
    @ValueSource(strings = ["image", "bookmark", "paragraph"])
    fun `malformed body URLs raise a mapping failure without exposing the URL`(blockType: String) {
        enqueuePage("\"https://workspace.notion.site/published-page\"")
        val invalidUrl = "https://example.com/private path"
        val payload =
            when (blockType) {
                "image" -> """{"type":"external","external":{"url":"$invalidUrl"},"caption":[]}"""
                "bookmark" -> """{"url":"$invalidUrl","caption":[]}"""
                else -> """{"rich_text":[{"type":"text","text":{"content":"Link","link":{"url":"$invalidUrl"}},"annotations":{}}]}"""
            }
        enqueueChildren("""{"id":"body-block","type":"$blockType","has_children":false,"in_trash":false,"$blockType":$payload}""")

        assertThatThrownBy { source().fetch(reference) }
            .isInstanceOf(SourceMappingException::class.java)
            .hasMessageNotContaining(invalidUrl)
    }

    @Test
    fun `missing publication URL still imports the page`() {
        enqueuePage(null)
        enqueueChildren()

        val imported = source().fetch(reference)

        assertThat(imported.title).isEqualTo("Incoming title")
    }

    @Test
    fun `explicit null publication URL still imports the page`() {
        enqueuePage("null")
        enqueueChildren()

        val imported = source().fetch(reference)

        assertThat(imported.title).isEqualTo("Incoming title")
    }

    private fun source() =
        NotionPostSource(
            sourceId = reference.sourceId,
            client = NotionApiClient(server.url("/v1").toString(), "test-token", Duration.ofSeconds(1), Duration.ofSeconds(5)),
            maxDepth = 8,
            maxBlockCount = 100,
            collectionTimeout = Duration.ofSeconds(5),
            nanoTime = { 0L },
        )

    private fun enqueuePage(publicUrl: String?) {
        val publicationField = publicUrl?.let { "\"public_url\":$it," }.orEmpty()
        enqueueJson(
            """{
                "id":"${reference.externalId}",
                "parent":{"type":"workspace","workspace":true},
                "url":"https://www.notion.so/${reference.externalId}",
                $publicationField
                "in_trash":false,
                "last_edited_time":"2026-10-04T00:00:00Z",
                "properties":{"Name":{"type":"title","title":[{"plain_text":"Incoming title"}]}}
            }""",
        )
    }

    private fun enqueueChildren(block: String = "") {
        enqueueJson("""{"results":[$block],"has_more":false,"next_cursor":null}""")
    }

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.trimIndent()))
    }
}
