package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.DATABASE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.DATA_SOURCE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.QUERY
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.ROOT
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.SYNCED_ORIGIN
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.SYNCED_REFERENCE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.dataSets
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.database
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.json
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.pageOf
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.query
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.row
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.source
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.stubDefaults
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.text
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.view
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import java.net.URI

class NotionInlineDatabaseSourceTest {
    private val client = mockk<NotionApiClient>()

    @Test
    fun `keeps inaccessible display views as a safe link after ownership is checked`() {
        stubDefaults(client)
        every { client.fetchDatabaseViews(DATABASE, null) } throws SourceAccessException()

        val database =
            source(client)
                .fetch(reference())
                .content.roots
                .single()

        assertThat(dataSets(database)).isEmpty()
        assertThat((database.content as ReferenceBlockContent.DatabaseLink).originalUrl).isEqualTo(URI("https://www.notion.so/$DATABASE"))
    }

    @Test
    fun `propagates inaccessible database metadata and temporary failures`() {
        stubDefaults(client)
        every { client.fetchDatabase(DATABASE) } throws SourceAccessException()
        assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(SourceAccessException::class.java)

        every { client.fetchDatabase(DATABASE) } throws RetryableSourceException()
        assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `removes rows that became inaccessible without retaining their previous cells`() {
        stubDefaults(client)
        val first = source(client).fetch(reference())
        every { client.fetchPage(ROW, listOf("title", "status")) } throws SourceAccessException()

        val second = source(client).fetch(reference())

        assertThat(dataSets(second.content.roots.single()).single().rows).isEmpty()
        assertThat(second.sourceRevision).isNotEqualTo(first.sourceRevision)
    }

    @Test
    fun `revises a snapshot when row values or visibility change while the parent revision stays the same`() {
        stubDefaults(client)
        val first = source(client).fetch(reference())
        assertThat(source(client).fetch(reference()).sourceRevision).isEqualTo(first.sourceRevision)
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW, "Changed")

        val changed = source(client).fetch(reference())

        assertThat(changed.sourceRevision).isNotEqualTo(first.sourceRevision)
        assertThat(changed.sourceRevision.value).doesNotContain("Changed", "Hidden secret")
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW).copy(publicUrl = null)
        val withoutPublicUrl = source(client).fetch(reference())
        assertThat(withoutPublicUrl.sourceRevision).isNotEqualTo(changed.sourceRevision)
        assertThat(dataSets(withoutPublicUrl.content.roots.single()).single().rows.map { text(it.cells[0]) }).containsExactly("First")
    }

    @Test
    fun `applies row column and cell budgets again when copying a synchronized database table`() {
        stubDefaults(client)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                NotionBlockEnvelope(SYNCED_ORIGIN, "synced_block", true, false, json.readTree("""{"synced_from":null}""")),
                NotionBlockEnvelope(
                    SYNCED_REFERENCE,
                    "synced_block",
                    true,
                    false,
                    json.readTree("""{"synced_from":{"type":"block_id","block_id":"$SYNCED_ORIGIN"}}"""),
                ),
            )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns
            listOf(
                NotionBlockEnvelope(DATABASE, "child_database", true, false, json.readTree("""{"title":"Projects"}""")),
            )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns emptyList()

        val imported = source(client).fetch(reference())

        assertThat(imported.content.roots.flatMap(::dataSets)).hasSize(2)
        assertThatThrownBy { source(client, maxBlockCount = 15).fetch(reference()) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects foreign views and rows instead of rendering an unrelated source`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns view().copy(databaseId = DATA_SOURCE)
        assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        every { client.fetchDatabaseView(VIEW) } returns view()
        every { client.fetchPage(ROW, listOf("title", "status")) } returns
            row(ROW).copy(parent = NotionPageParentResponse("data_source_id", null, DATABASE))
        assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects repeated query rows and pagination cursors instead of hanging or duplicating data`() {
        stubDefaults(client)
        every { client.createViewQuery(VIEW) } returns query(listOf(ROW), "next")
        every { client.fetchViewQueryResults(VIEW, QUERY, "next") } returns pageOf(listOf(ROW))
        assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        every { client.fetchViewQueryResults(VIEW, QUERY, "next") } returns pageOf(emptyList(), "next")
        assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `treats an expired query as a failed import rather than publishing a partial table`() {
        stubDefaults(client)
        every { client.createViewQuery(VIEW) } returns query(listOf(ROW), "next")
        every { client.fetchViewQueryResults(VIEW, QUERY, "next") } throws SourceAccessException()
        assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `applies collection budgets and nesting limits to synthetic database tables`() {
        stubDefaults(client)
        assertThatThrownBy { source(client, maxBlockCount = 3).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        assertThatThrownBy { source(client, maxDepth = 2).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `checks the overall deadline between database requests`() {
        stubDefaults(client)
        var expired = false
        every { client.fetchDatabase(DATABASE) } answers {
            expired = true
            database()
        }
        assertThatThrownBy { source(client, nanoTime = { if (expired) 2_000_000_000L else 0L }).fetch(reference()) }
            .isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `does not import a database that is only linked in the page body`() {
        stubDefaults(client)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                NotionBlockEnvelope(
                    "link",
                    "link_to_page",
                    false,
                    false,
                    json.readTree("""{"type":"database_id","database_id":"$DATABASE"}"""),
                ),
            )
        val imported = source(client).fetch(reference())
        assertThat(
            imported.content.roots
                .single()
                .content,
        ).isInstanceOf(ReferenceBlockContent.DatabaseLink::class.java)
        assertThat(
            imported.content.roots
                .single()
                .children,
        ).isEmpty()
    }
}
