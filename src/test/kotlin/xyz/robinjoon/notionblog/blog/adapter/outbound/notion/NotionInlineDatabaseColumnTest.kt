package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.DATA_SOURCE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.SECOND_VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.dataSets
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.query
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.row
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.source
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.stubDefaults
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.text
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.view
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException

class NotionInlineDatabaseColumnTest {
    private val client = mockk<NotionApiClient>()

    @Test
    fun `imports only the title when visible property settings are absent and revises the previous fallback`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = emptyList())
        val previous = source(client).fetch(reference())
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = null)
        every { client.fetchPage(ROW, listOf("title")) } returns row(ROW)

        val imported = source(client).fetch(reference())

        val data = dataSets(imported.content.roots.single()).single()
        assertThat(data.columns.map { it.name }).containsExactly("Name")
        assertThat(data.titleColumnIndex).isZero()
        assertThat(
            data.rows
                .single()
                .cells
                .map(::text),
        ).containsExactly("First")
        assertThat(data.toString()).doesNotContain("Hidden secret", "Status", "Done")
        assertThat(imported.sourceRevision).isNotEqualTo(previous.sourceRevision)
        assertThat(source(client).fetch(reference()).sourceRevision).isEqualTo(imported.sourceRevision)
    }

    @Test
    fun `does not restore explicitly empty columns or render a view without its source or layout`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = emptyList())
        assertThat(
            dataSets(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ),
        ).isEmpty()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(dataSourceId = null, columns = null)
        assertThat(
            dataSets(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ),
        ).isEmpty()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(configuration = null)
        assertThat(
            dataSets(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ),
        ).isEmpty()
    }

    @Test
    fun `rejects default schemas without a unique title rather than choosing another property`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = null)
        listOf(
            listOf(NotionDatabaseProperty("status", "Status", "status")),
            listOf(NotionDatabaseProperty("title", "Name", "title"), NotionDatabaseProperty("second-title", "Other", "title")),
            listOf(NotionDatabaseProperty("title", "Name", "title"), NotionDatabaseProperty("title", "Duplicate", "rich_text")),
        ).forEach { properties ->
            every { client.fetchDataSource(DATA_SOURCE) } returns
                NotionDataSourceResponse(DATA_SOURCE, properties, NotionParentResponse.Database(SECOND_VIEW), false)

            assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        }
    }

    @Test
    fun `distinguishes column boundaries in empty data sets`() {
        stubDefaults(client)
        every { client.createViewQuery(VIEW) } returns query(emptyList())
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = listOf(NotionViewColumn("title", "Name, Status")))

        val singleColumn = source(client).fetch(reference())
        every { client.fetchDatabaseView(VIEW) } returns view()
        val twoColumns = source(client).fetch(reference())

        assertThat(dataSets(singleColumn.content.roots.single()).single().columns.map { it.name }).containsExactly("Name, Status")
        assertThat(dataSets(twoColumns.content.roots.single()).single().columns.map { it.name }).containsExactly("Name", "Status")
        assertThat(singleColumn.sourceRevision).isNotEqualTo(twoColumns.sourceRevision)
    }
}
