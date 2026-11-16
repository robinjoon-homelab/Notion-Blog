package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPostsConfiguration
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionRevisionSource
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.time.Duration
import java.time.Instant

class NotionMigrationRevisionTest {
    private val server = MockWebServer()
    private val reference = SourceDocumentRef(SourceId("notion"), PAGE)
    private val migration = MigrationSelection(DATA_SOURCE, MigrationFields("heading", "sum", "tag", "date"))

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `migration mapping imports properties from a different source without changing the configured source`() {
        enqueuePage("""{"start":"2023-07-15","end":null,"time_zone":null}""")

        val revision = source().fetchRevision(reference, migration)

        assertThat(revision.properties.title).isEqualTo("Mapped title")
        assertThat(revision.properties.summary).isEqualTo("Old summary")
        assertThat(revision.properties.tags).containsExactly("Kotlin")
        assertThat(revision.properties.cover).isInstanceOf(MediaSource.External::class.java)
        assertThat(revision.authoredAt).isEqualTo(Instant.parse("2023-07-15T00:00:00Z"))
        assertThat(revision.sourceDocument).isEqualTo(reference)
    }

    @ParameterizedTest
    @CsvSource(
        "2023-07-15T12:30:00+09:00, 2023-07-15T03:30:00Z",
        "2023-07-15T12:30:00Z, 2023-07-15T12:30:00Z",
        "2023-07-15T12:30:00, 2023-07-15T12:30:00Z",
    )
    fun `authored timestamps preserve the offset and use UTC for a time without a zone`(
        value: String,
        expected: String,
    ) {
        enqueuePage("""{"start":"$value","time_zone":null}""")

        assertThat(source().fetchRevision(reference, migration).authoredAt).isEqualTo(Instant.parse(expected))
    }

    @Test
    fun `authored timestamps honor a named Notion timezone`() {
        enqueuePage("""{"start":"2023-07-15T12:30:00","time_zone":"Asia/Seoul"}""")

        assertThat(source().fetchRevision(reference, migration).authoredAt).isEqualTo(Instant.parse("2023-07-15T03:30:00Z"))
    }

    @Test
    fun `mapped created time and select properties can be imported`() {
        val extra = """"Created":{"id":"created","type":"created_time","created_time":"2021-02-03T04:05:06Z"}"""
        enqueuePage("null", extra)
        val fields = migration.fields.copy(tagsPropertyId = "select", authoredAtPropertyId = "created")

        val revision = source().fetchRevision(reference, migration.copy(fields = fields))

        assertThat(revision.authoredAt).isEqualTo(Instant.parse("2021-02-03T04:05:06Z"))
        assertThat(revision.properties.tags).containsExactly("Books")
    }

    @Test
    fun `an empty or invalid mapped authored date fails instead of substituting the import time`() {
        listOf("null", """{"start":"not-a-date"}""").forEach { date ->
            enqueuePage(date)

            assertThatThrownBy { source().fetchRevision(reference, migration) }.isInstanceOf(SourceMappingException::class.java)
        }
    }

    @Test
    fun `an unmapped authored date stays absent`() {
        enqueuePage("null")

        val revision = source().fetchRevision(reference, migration.copy(fields = migration.fields.copy(authoredAtPropertyId = "")))

        assertThat(revision.authoredAt).isNull()
    }

    @Test
    fun `a page outside the selected source is rejected`() {
        enqueue(page("null").replace("\"data_source_id\":\"$DATA_SOURCE\"", "\"data_source_id\":\"$OTHER\""))

        assertThatThrownBy { source().fetchRevision(reference, migration) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `an incompatible mapped property is rejected`() {
        enqueuePage("null")
        val fields = migration.fields.copy(summaryPropertyId = "tag")

        assertThatThrownBy { source().fetchRevision(reference, migration.copy(fields = fields)) }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `an empty mapped title fails that import`() {
        enqueue(page("null").replace("Mapped title", ""))
        enqueue("""{"results":[],"has_more":false,"next_cursor":null}""")

        assertThatThrownBy { source().fetchRevision(reference, migration) }.isInstanceOf(SourceMappingException::class.java)
    }

    private fun source(): NotionRevisionSource {
        val client = NotionApiClient(server.url("/v1").toString(), "test-token", Duration.ofSeconds(1), Duration.ofSeconds(5))
        return NotionRevisionSource(
            reference.sourceId,
            NotionPostsConfiguration(OTHER),
            NotionPostSource(reference.sourceId, client, 8, 100, Duration.ofSeconds(5)),
        )
    }

    private fun enqueuePage(
        date: String,
        extra: String = "",
    ) {
        enqueue(page(date, extra))
        enqueue("""{"results":[],"has_more":false,"next_cursor":null}""")
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun page(
        date: String,
        extra: String = "",
    ): String {
        val more = if (extra.isBlank()) "" else ",$extra"
        return """{"object":"page","id":"$PAGE","parent":{"type":"data_source_id","data_source_id":"$DATA_SOURCE"},
            "url":"https://www.notion.so/$PAGE","in_trash":false,"last_edited_time":"2026-10-05T00:00:00Z",
            "cover":{"type":"external","external":{"url":"https://example.com/cover.png"}},"properties":{
            "Name":{"id":"title","type":"title","title":[{"plain_text":"Unmapped title"}]},
            "Heading":{"id":"heading","type":"rich_text","rich_text":[{"plain_text":"Mapped title"}]},
            "Summary":{"id":"sum","type":"rich_text","rich_text":[{"plain_text":"Old summary"}]},
            "Tags":{"id":"tag","type":"multi_select","multi_select":[{"name":"Kotlin"},{"name":"Kotlin"}]},
            "Select":{"id":"select","type":"select","select":{"name":"Books"}},
            "Written":{"id":"date","type":"date","date":$date}$more}}"""
    }

    private companion object {
        const val PAGE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATA_SOURCE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER = "cccccccccccccccccccccccccccccccc"
    }
}
