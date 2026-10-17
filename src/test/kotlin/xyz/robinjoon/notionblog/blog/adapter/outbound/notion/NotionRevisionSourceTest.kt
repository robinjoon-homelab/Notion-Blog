package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPostsConfiguration
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionRevisionSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.net.URI
import java.time.Duration

class NotionRevisionSourceTest {
    private val server = MockWebServer()
    private val reference = SourceDocumentRef(SourceId("notion"), PAGE)

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `imports a row without a public URL with body summary tags and cover`() {
        enqueue(page(parent = dataSourceParent(DATA_SOURCE), publicUrl = "null", extra = SUMMARY_AND_TAGS))
        enqueue(children(paragraph("body-1")))

        val revision = source().fetchRevision(reference)

        assertThat(revision.sourceDocument).isEqualTo(reference)
        assertThat(revision.properties.title).isEqualTo("Hello")
        assertThat(revision.properties.summary).isEqualTo("A short summary")
        assertThat(revision.properties.tags).containsExactly("kotlin", "notion")
        assertThat(revision.properties.cover).isEqualTo(MediaSource.External(URI("https://example.com/cover.png")))
        assertThat(revision.sourceRevision.value).isEqualTo("2026-10-05T00:00:00Z")
        assertThat(
            revision.content.roots
                .single()
                .content,
        ).isInstanceOf(TextBlockContent.Paragraph::class.java)
    }

    @Test
    fun `imports a row whose response has no public_url field at all`() {
        enqueue(page(parent = dataSourceParent(DATA_SOURCE), publicUrl = null))
        enqueue(children())

        val revision = source().fetchRevision(reference)

        assertThat(revision.properties.title).isEqualTo("Hello")
        assertThat(revision.properties.summary).isNull()
        assertThat(revision.properties.tags).isEmpty()
    }

    @Test
    fun `a legacy page outside the data source keeps only its cover`() {
        enqueue(page(parent = """{"type":"page_id","page_id":"$OTHER"}""", publicUrl = "null", extra = SUMMARY_AND_TAGS))
        enqueue(children())

        val properties = source().fetchRevision(reference).properties

        assertThat(properties.summary).isNull()
        assertThat(properties.tags).isEmpty()
        assertThat(properties.cover).isNotNull()
    }

    @Test
    fun `a trashed page stops the refresh before any block is requested`() {
        enqueue(page(parent = dataSourceParent(DATA_SOURCE), publicUrl = "null", inTrash = true))

        assertThatThrownBy { source().fetchRevision(reference) }.isInstanceOf(SourceAccessException::class.java)

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a mapped property with an unexpected type is a configuration failure`() {
        val wrongTags = """"Tags":{"id":"tags","type":"rich_text","rich_text":[]}"""
        enqueue(page(parent = dataSourceParent(DATA_SOURCE), publicUrl = "null", extra = wrongTags))
        enqueue(children())

        assertThatThrownBy { source().fetchRevision(reference) }.isInstanceOf(SourceConfigurationException::class.java)
    }

    private fun source(): NotionRevisionSource {
        val sourceId = reference.sourceId
        val client = NotionApiClient(server.url("/v1").toString(), "test-token", Duration.ofSeconds(1), Duration.ofSeconds(5))
        val pages = NotionPostSource(sourceId, client, 8, 100, Duration.ofSeconds(5))
        return NotionRevisionSource(sourceId, NotionPostsConfiguration(DATA_SOURCE, SUMMARY_ID, TAGS_ID), pages)
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.trimIndent()))
    }

    private fun dataSourceParent(id: String) = """{"type":"data_source_id","data_source_id":"$id","database_id":"db"}"""

    private fun page(
        parent: String,
        publicUrl: String?,
        inTrash: Boolean = false,
        extra: String = "",
    ): String {
        val publicField = publicUrl?.let { """"public_url":$it,""" }.orEmpty()
        val more = if (extra.isBlank()) "" else ",$extra"
        return """{"object":"page","id":"$PAGE","parent":$parent,"url":"https://www.notion.so/$PAGE",$publicField
            "in_trash":$inTrash,"last_edited_time":"2026-10-05T00:00:00Z",
            "cover":{"type":"external","external":{"url":"https://example.com/cover.png"}},
            "properties":{"Name":{"id":"title","type":"title","title":[{"plain_text":"Hello"}]}$more}}"""
    }

    private fun children(vararg blocks: String) = """{"results":[${blocks.joinToString(",")}],"has_more":false,"next_cursor":null}"""

    private fun paragraph(id: String) =
        """{"id":"$id","type":"paragraph","has_children":false,"in_trash":false,
            "paragraph":{"rich_text":[{"type":"text","text":{"content":"Body"},"plain_text":"Body","annotations":{}}]}}"""

    private companion object {
        const val PAGE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DATA_SOURCE = "cccccccccccccccccccccccccccccccc"
        const val SUMMARY_ID = "%3AUo%40"
        const val TAGS_ID = "tags"
        const val SUMMARY_AND_TAGS =
            """"Summary":{"id":"%3AUo%40","type":"rich_text","rich_text":[{"plain_text":"A short "},{"plain_text":"summary"}]},
            "Tags":{"id":"tags","type":"multi_select","multi_select":[{"name":"kotlin"},{"name":"notion"},{"name":"kotlin"}]}"""
    }
}
