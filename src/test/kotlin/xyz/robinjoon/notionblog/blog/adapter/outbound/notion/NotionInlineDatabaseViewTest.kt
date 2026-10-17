package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.DATABASE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.PRIVATE_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.QUERY
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.SECOND_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.SECOND_VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.TRASH_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.dataSets
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.dataViews
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.database
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.nodes
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.pageOf
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.query
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.row
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.source
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.sourceId
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.stubDefaults
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.text
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.view
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionGalleryAspect
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionGalleryLayout
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionGallerySize
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import java.net.URI

class NotionInlineDatabaseViewTest {
    private val client = mockk<NotionApiClient>()

    @Test
    fun `imports ordered visible columns and every readable non-trashed row through the saved view`() {
        stubDefaults(client)
        every { client.createViewQuery(VIEW) } returns query(listOf(ROW), "next")
        every { client.fetchViewQueryResults(VIEW, QUERY, "next") } returns pageOf(listOf(PRIVATE_ROW, TRASH_ROW, SECOND_ROW))
        every { client.fetchPage(PRIVATE_ROW, listOf("title", "status")) } returns row(PRIVATE_ROW).copy(publicUrl = null)
        every { client.fetchPage(TRASH_ROW, listOf("title", "status")) } returns row(TRASH_ROW).copy(inTrash = true)
        every { client.fetchPage(SECOND_ROW, listOf("title", "status")) } returns row(SECOND_ROW, "Second")

        val imported = source(client).fetch(reference())

        val database = imported.content.roots.single()
        assertThat(
            database.content,
        ).isEqualTo(
            ReferenceBlockContent.DatabaseLink(SourceDocumentRef(sourceId, DATABASE), URI("https://www.notion.so/$DATABASE"), "Projects"),
        )
        assertThat(database.children.single().content).isEqualTo(LayoutBlockContent.TabContainer)
        val table = dataSets(database).single()
        assertThat(table.title).isEqualTo("Published projects")
        assertThat(table.columns.map { it.name }).containsExactly("Name", "Status")
        assertThat(table.titleColumnIndex).isZero()
        assertThat(
            table.rows.first().link,
        ).isEqualTo(LinkTarget.SourceDocument(SourceDocumentRef(sourceId, ROW), URI("https://site.notion.site/$ROW")))
        assertThat(table.rows.map { text(it.cells[0]) }).containsExactly("First", "First", "Second")
        assertThat(table.rows.map { text(it.cells[1]) }).containsExactly("Done", "Done", "Done")
        assertThat(table.rows[1].link).isEqualTo(LinkTarget.SourceDocument(SourceDocumentRef(sourceId, PRIVATE_ROW), null))
        assertThat(table.toString()).doesNotContain("Hidden secret", TRASH_ROW)
        assertThat(imported.containedChildren).isEmpty()
    }

    @Test
    fun `keeps supported database views in tabs`() {
        stubDefaults(client)
        every { client.fetchDatabaseViews(DATABASE, null) } returns pageOf(listOf(VIEW), "more-views")
        every { client.fetchDatabaseViews(DATABASE, "more-views") } returns pageOf(listOf(SECOND_VIEW))
        every { client.fetchDatabaseView(SECOND_VIEW) } returns
            view(SECOND_VIEW).copy(name = "List", type = "list", configuration = NotionViewConfiguration.ListView)
        every { client.createViewQuery(SECOND_VIEW) } returns query(emptyList(), viewId = SECOND_VIEW)

        val database =
            source(client)
                .fetch(reference())
                .content.roots
                .single()

        assertThat(dataSets(database).map { it.title }).containsExactly("Published projects", "List")
        assertThat(dataSets(database)[1].rows).isEmpty()
    }

    @Test
    fun `renders only table list and gallery from mixed view types`() {
        stubDefaults(client)
        val types = listOf("board", "table", "calendar", "list", "timeline", "gallery", "chart", "map", "form", "dashboard", "future")
        val ids = types.mapIndexed { index, _ -> (index + 100).toString(16).padStart(32, '0') }
        every { client.fetchDatabaseViews(DATABASE, null) } returns pageOf(ids)
        types.zip(ids).forEach { (type, id) ->
            val configuration =
                when (type) {
                    "table" -> NotionViewConfiguration.Table()
                    "list" -> NotionViewConfiguration.ListView
                    "gallery" -> NotionViewConfiguration.Gallery()
                    else -> null
                }
            every { client.fetchDatabaseView(id) } returns view(id).copy(type = type, name = type, configuration = configuration)
            if (configuration != null) every { client.createViewQuery(id) } returns query(emptyList(), viewId = id)
        }

        val database =
            source(client)
                .fetch(reference())
                .content.roots
                .single()
        val views = dataViews(database)

        assertThat(views.map { it.data.title }).containsExactly("table", "list", "gallery")
        assertThat(views[0]).isInstanceOf(DataViewContent.Table::class.java)
        assertThat(views[1]).isInstanceOf(DataViewContent.ListView::class.java)
        assertThat(views[2]).isInstanceOf(DataViewContent.Gallery::class.java)
        assertThat(database.children.single().children).hasSize(3)
        assertThat(nodes(database).none { it.content is UnsupportedBlockContent }).isTrue()
    }

    @Test
    fun `omits an unsupported only database and revises a previously supported snapshot`() {
        stubDefaults(client)
        val supported = source(client).fetch(reference())
        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "board", configuration = null)

        val excluded = source(client).fetch(reference())

        assertThat(excluded.content.roots).isEmpty()
        assertThat(excluded.sourceRevision).isNotEqualTo(supported.sourceRevision)
    }

    @Test
    fun `preserves layout options and revises layout width wrap and gallery changes without parent edits`() {
        stubDefaults(client)
        val initial = source(client).fetch(reference())
        every { client.fetchDatabaseView(VIEW) } returns
            view().copy(
                columns = listOf(NotionViewColumn("title", null, 320, false), NotionViewColumn("status", null, 160, true)),
                configuration = NotionViewConfiguration.Table(wrapCells = false, frozenColumns = 1, showVerticalLines = false),
            )
        val styled = source(client).fetch(reference())
        val table = dataViews(styled.content.roots.single()).single() as DataViewContent.Table
        assertThat(table.options).isEqualTo(DataTableOptions(wrapCells = false, frozenColumns = 1, showVerticalLines = false))
        assertThat(table.data.columns.map { it.widthPixels }).containsExactly(320, 160)
        assertThat(table.data.columns.map { it.wrap }).containsExactly(false, true)
        assertThat(styled.sourceRevision).isNotEqualTo(initial.sourceRevision)

        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "list", configuration = NotionViewConfiguration.ListView)
        val list = source(client).fetch(reference())
        assertThat(dataViews(list.content.roots.single()).single()).isInstanceOf(DataViewContent.ListView::class.java)
        assertThat(list.sourceRevision).isNotEqualTo(styled.sourceRevision)

        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "gallery", configuration = NotionViewConfiguration.Gallery())
        val gallery = source(client).fetch(reference())
        assertThat(gallery.sourceRevision).isNotEqualTo(list.sourceRevision)
        every { client.fetchDatabaseView(VIEW) } returns
            view().copy(
                type = "gallery",
                configuration =
                    NotionViewConfiguration.Gallery(
                        size = NotionGallerySize.LARGE,
                        aspect = NotionGalleryAspect.CONTAIN,
                        layout = NotionGalleryLayout.COMPACT,
                    ),
            )
        val styledGallery = source(client).fetch(reference())
        assertThat((dataViews(styledGallery.content.roots.single()).single() as DataViewContent.Gallery).options)
            .isEqualTo(DataGalleryOptions(DataCardSize.LARGE, DataCoverAspect.CONTAIN, DataCardLayout.COMPACT))
        assertThat(styledGallery.sourceRevision).isNotEqualTo(gallery.sourceRevision)
    }

    @Test
    fun `does not synthesize a hidden title column for list cards`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns
            view().copy(
                type = "list",
                columns = listOf(NotionViewColumn("status", null)),
                configuration = NotionViewConfiguration.ListView,
            )
        every { client.fetchPage(ROW, listOf("status")) } returns row(ROW)

        val data =
            dataSets(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ).single()

        assertThat(data.columns.map { it.name }).containsExactly("Status")
        assertThat(data.titleColumnIndex).isNull()
        assertThat(
            data.rows
                .single()
                .cells
                .map(::text),
        ).containsExactly("Done")
    }

    @Test
    fun `limits frozen columns to the visible columns without restoring hidden properties`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns view().copy(configuration = NotionViewConfiguration.Table(frozenColumns = 5))

        val table =
            dataViews(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ).single() as DataViewContent.Table

        assertThat(table.options.frozenColumns).isEqualTo(2)
        assertThat(table.data.columns.map { it.name }).containsExactly("Name", "Status")
    }
}
