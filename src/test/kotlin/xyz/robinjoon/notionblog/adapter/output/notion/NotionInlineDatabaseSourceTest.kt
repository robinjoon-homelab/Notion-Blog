package xyz.robinjoon.notionblog.adapter.output.notion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryAspect
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryLayout
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGallerySize
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewQueryResponse
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.time.Duration
import java.time.Instant

class NotionInlineDatabaseSourceTest {
    private val json = JsonMapper.builder().build()
    private val client = mockk<NotionApiClient>()
    private val sourceId = SourceId("notion-main")

    @Test
    fun `imports ordered visible columns and published rows through the saved view without widening publication`() {
        fixture()
        every { client.createViewQuery(VIEW) } returns query(listOf(ROW), "next")
        every { client.fetchViewQueryResults(VIEW, QUERY, "next") } returns pageOf(listOf(PRIVATE_ROW, TRASH_ROW, SECOND_ROW))
        every { client.fetchPage(PRIVATE_ROW, listOf("title", "status")) } returns row(PRIVATE_ROW).copy(publicUrl = null)
        every { client.fetchPage(TRASH_ROW, listOf("title", "status")) } returns row(TRASH_ROW).copy(inTrash = true)
        every { client.fetchPage(SECOND_ROW, listOf("title", "status")) } returns row(SECOND_ROW, "Second")

        val imported = source().fetch(reference())

        val database = imported.content.roots.single()
        assertThat(database.content).isEqualTo(ReferenceBlockContent.DatabaseLink(SourceDocumentRef(sourceId, DATABASE), URI("https://www.notion.so/$DATABASE"), "Projects"))
        assertThat(database.children.single().content).isEqualTo(LayoutBlockContent.TabContainer)
        val table = dataSets(database).single()
        assertThat(table.title).isEqualTo("Published projects")
        assertThat(table.columns.map { it.name }).containsExactly("Name", "Status")
        assertThat(table.titleColumnIndex).isZero()
        assertThat(table.rows.first().link).isEqualTo(LinkTarget.SourceDocument(SourceDocumentRef(sourceId, ROW), URI("https://site.notion.site/$ROW")))
        assertThat(table.rows.map { text(it.cells[0]) }).containsExactly("First", "Second")
        assertThat(table.rows.map { text(it.cells[1]) }).containsExactly("Done", "Done")
        assertThat(table.toString()).doesNotContain("Hidden secret", PRIVATE_ROW, TRASH_ROW)
        assertThat(imported.containedChildren).isEmpty()
        verify(exactly = 0) { client.fetchDirectBlockChildren(DATABASE) }
        verify(exactly = 0) { client.fetchDirectBlockChildren(ROW) }
    }

    @Test
    fun `keeps supported database views in tabs and reuses the source schema`() {
        fixture()
        every { client.fetchDatabaseViews(DATABASE, null) } returns pageOf(listOf(VIEW), "more-views")
        every { client.fetchDatabaseViews(DATABASE, "more-views") } returns pageOf(listOf(SECOND_VIEW))
        every { client.fetchDatabaseView(SECOND_VIEW) } returns view(SECOND_VIEW).copy(name = "List", type = "list", configuration = NotionViewConfiguration.ListView)
        every { client.createViewQuery(SECOND_VIEW) } returns query(emptyList(), viewId = SECOND_VIEW)

        val database = source().fetch(reference()).content.roots.single()

        assertThat(dataSets(database).map { it.title }).containsExactly("Published projects", "List")
        assertThat(dataSets(database)[1].rows).isEmpty()
        verify(exactly = 1) { client.fetchDataSource(DATA_SOURCE) }
    }

    @Test
    fun `renders only table list and gallery from mixed view types without querying excluded views`() {
        fixture()
        val types = listOf("board", "table", "calendar", "list", "timeline", "gallery", "chart", "map", "form", "dashboard", "future")
        val ids = types.mapIndexed { index, _ -> (index + 100).toString(16).padStart(32, '0') }
        every { client.fetchDatabaseViews(DATABASE, null) } returns pageOf(ids)
        types.zip(ids).forEach { (type, id) ->
            val configuration = when (type) {
                "table" -> NotionViewConfiguration.Table()
                "list" -> NotionViewConfiguration.ListView
                "gallery" -> NotionViewConfiguration.Gallery()
                else -> null
            }
            every { client.fetchDatabaseView(id) } returns view(id).copy(type = type, name = type, configuration = configuration)
            if (configuration != null) every { client.createViewQuery(id) } returns query(emptyList(), viewId = id)
        }

        val database = source().fetch(reference()).content.roots.single()
        val views = dataViews(database)

        assertThat(views.map { it.data.title }).containsExactly("table", "list", "gallery")
        assertThat(views[0]).isInstanceOf(DataViewContent.Table::class.java)
        assertThat(views[1]).isInstanceOf(DataViewContent.ListView::class.java)
        assertThat(views[2]).isInstanceOf(DataViewContent.Gallery::class.java)
        assertThat(database.children.single().children).hasSize(3)
        assertThat(nodes(database).none { it.content is UnsupportedBlockContent }).isTrue()
        types.zip(ids).filter { it.first !in setOf("table", "list", "gallery") }.forEach { (_, id) ->
            verify(exactly = 0) { client.createViewQuery(id) }
        }
        verify(exactly = 1) { client.fetchDataSource(DATA_SOURCE) }
    }

    @Test
    fun `omits an unsupported only database and revises a previously supported snapshot`() {
        fixture()
        val supported = source().fetch(reference())
        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "board", configuration = null)

        val excluded = source().fetch(reference())

        assertThat(excluded.content.roots).isEmpty()
        assertThat(excluded.sourceRevision).isNotEqualTo(supported.sourceRevision)
        verify(exactly = 1) { client.fetchDataSource(DATA_SOURCE) }
        verify(exactly = 1) { client.createViewQuery(VIEW) }
    }

    @Test
    fun `preserves layout options and revises layout width wrap and gallery changes without parent edits`() {
        fixture()
        val initial = source().fetch(reference())
        every { client.fetchDatabaseView(VIEW) } returns view().copy(
            columns = listOf(NotionViewColumn("title", null, 320, false), NotionViewColumn("status", null, 160, true)),
            configuration = NotionViewConfiguration.Table(wrapCells = false, frozenColumns = 1, showVerticalLines = false),
        )
        val styled = source().fetch(reference())
        val table = dataViews(styled.content.roots.single()).single() as DataViewContent.Table
        assertThat(table.options).isEqualTo(DataTableOptions(wrapCells = false, frozenColumns = 1, showVerticalLines = false))
        assertThat(table.data.columns.map { it.widthPixels }).containsExactly(320, 160)
        assertThat(table.data.columns.map { it.wrap }).containsExactly(false, true)
        assertThat(styled.sourceRevision).isNotEqualTo(initial.sourceRevision)

        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "list", configuration = NotionViewConfiguration.ListView)
        val list = source().fetch(reference())
        assertThat(dataViews(list.content.roots.single()).single()).isInstanceOf(DataViewContent.ListView::class.java)
        assertThat(list.sourceRevision).isNotEqualTo(styled.sourceRevision)

        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "gallery", configuration = NotionViewConfiguration.Gallery())
        val gallery = source().fetch(reference())
        assertThat(gallery.sourceRevision).isNotEqualTo(list.sourceRevision)
        every { client.fetchDatabaseView(VIEW) } returns view().copy(
            type = "gallery",
            configuration = NotionViewConfiguration.Gallery(size = NotionGallerySize.LARGE, aspect = NotionGalleryAspect.CONTAIN, layout = NotionGalleryLayout.COMPACT),
        )
        val styledGallery = source().fetch(reference())
        assertThat((dataViews(styledGallery.content.roots.single()).single() as DataViewContent.Gallery).options)
            .isEqualTo(DataGalleryOptions(DataCardSize.LARGE, DataCoverAspect.CONTAIN, DataCardLayout.COMPACT))
        assertThat(styledGallery.sourceRevision).isNotEqualTo(gallery.sourceRevision)
    }

    @Test
    fun `does not fetch or synthesize a hidden title column for list cards`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(
            type = "list",
            columns = listOf(NotionViewColumn("status", null)),
            configuration = NotionViewConfiguration.ListView,
        )
        every { client.fetchPage(ROW, listOf("status")) } returns row(ROW)

        val data = dataSets(source().fetch(reference()).content.roots.single()).single()

        assertThat(data.columns.map { it.name }).containsExactly("Status")
        assertThat(data.titleColumnIndex).isNull()
        assertThat(data.rows.single().cells.map(::text)).containsExactly("Done")
        verify(exactly = 0) { client.fetchPage(ROW, listOf("title", "status")) }
    }

    @Test
    fun `limits frozen columns to the visible columns without restoring hidden properties`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(configuration = NotionViewConfiguration.Table(frozenColumns = 5))

        val table = dataViews(source().fetch(reference()).content.roots.single()).single() as DataViewContent.Table

        assertThat(table.options.frozenColumns).isEqualTo(2)
        assertThat(table.data.columns.map { it.name }).containsExactly("Name", "Status")
    }

    @Test
    fun `reads gallery covers only after publication and source parent checks`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(
            type = "gallery",
            configuration = NotionViewConfiguration.Gallery(cover = NotionGalleryCover.PageContent),
        )
        every { client.createViewQuery(VIEW) } returns query(listOf(PRIVATE_ROW, TRASH_ROW, ROW))
        every { client.fetchPage(PRIVATE_ROW, listOf("title", "status")) } returns row(PRIVATE_ROW).copy(publicUrl = null)
        every { client.fetchPage(TRASH_ROW, listOf("title", "status")) } returns row(TRASH_ROW).copy(inTrash = true)
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW).copy(parent = NotionPageParentResponse("data_source_id", null, DATABASE))

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)

        verify(exactly = 0) { client.fetchBlockChildrenPage(PRIVATE_ROW, any()) }
        verify(exactly = 0) { client.fetchBlockChildrenPage(TRASH_ROW, any()) }
        verify(exactly = 0) { client.fetchBlockChildrenPage(ROW, any()) }
    }

    @Test
    fun `uses row icons for lists and galleries but renders covers only for configured galleries`() {
        fixture()
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW).copy(
            icon = json.readTree("""{"type":"emoji","emoji":"📚"}"""),
            cover = json.readTree("""{"type":"external","external":{"url":"https://images.example/cover.png"}}"""),
        )

        val table = dataSets(source().fetch(reference()).content.roots.single()).single()
        assertThat(table.rows.single().icon).isNull()
        assertThat(table.rows.single().cover).isNull()

        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "list", configuration = NotionViewConfiguration.ListView)
        val list = dataSets(source().fetch(reference()).content.roots.single()).single()
        assertThat(list.rows.single().icon).isEqualTo(BlockIcon.Emoji("📚"))
        assertThat(list.rows.single().cover).isNull()

        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "gallery", configuration = NotionViewConfiguration.Gallery(cover = NotionGalleryCover.PageCover))
        val gallery = dataSets(source().fetch(reference()).content.roots.single()).single()
        assertThat(gallery.rows.single().icon).isEqualTo(BlockIcon.Emoji("📚"))
        assertThat(gallery.rows.single().cover).isEqualTo(MediaSource.External(URI("https://images.example/cover.png")))
        verify(exactly = 0) { client.fetchBlockChildrenPage(ROW, any()) }
    }

    @Test
    fun `applies the parent collection budget to all returned cover blocks before choosing the first image`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "gallery", configuration = NotionViewConfiguration.Gallery(cover = NotionGalleryCover.PageContent))
        val image = NotionBlockEnvelope("cover", "image", false, false, json.readTree("""{"type":"external","external":{"url":"https://images.example/cover.png"}}"""))
        val blocks = listOf(image) + (1..20).map { index -> NotionBlockEnvelope("discarded-$index", "paragraph", false, true, json.readTree("""{"rich_text":[]}""")) }
        every { client.fetchBlockChildrenPage(ROW, null) } returns NotionPaginationResponse(blocks, true, "unused-next")

        assertThat(dataSets(source().fetch(reference()).content.roots.single()).single().rows.single().cover)
            .isEqualTo(MediaSource.External(URI("https://images.example/cover.png")))
        assertThatThrownBy { source(maxBlockCount = 12).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.fetchBlockChildrenPage(ROW, "unused-next") }
    }

    @Test
    fun `requests an explicit hidden cover property without exposing it as a cell and retains signed expiry`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(
            type = "gallery",
            configuration = NotionViewConfiguration.Gallery(cover = NotionGalleryCover.Property("art")),
        )
        every { client.fetchDataSource(DATA_SOURCE) } returns NotionDataSourceResponse(
            DATA_SOURCE,
            listOf(NotionDatabaseProperty("title", "Name", "title"), NotionDatabaseProperty("status", "Status", "status"), NotionDatabaseProperty("art", "Hidden art", "files")),
            NotionParentResponse.Database(SECOND_VIEW),
            false,
        )
        fun covered(expiry: String) = row(ROW).copy(
            properties = json.readTree(
                row(ROW).properties.toString().dropLast(1) +
                    """, "Hidden art":{"id":"art","type":"files","files":[{"name":"cover.png","type":"file","file":{"url":"https://files.example/cover.png?signature=test","expiry_time":"$expiry"}}]}}""",
            ),
        )
        every { client.fetchPage(ROW, listOf("title", "status", "art")) } returns covered("2026-09-01T00:00:00Z")

        val first = source().fetch(reference())
        val data = dataSets(first.content.roots.single()).single()

        assertThat(data.columns.map { it.name }).containsExactly("Name", "Status")
        assertThat(data.rows.single().cells).hasSize(2)
        assertThat(data.rows.single().cover).isEqualTo(MediaSource.SourceHosted(URI("https://files.example/cover.png?signature=test"), Instant.parse("2026-09-01T00:00:00Z")))
        assertThat(data.rows.single().cells.toString()).doesNotContain("Hidden art", "cover.png")
        every { client.fetchPage(ROW, listOf("title", "status", "art")) } returns covered("2026-09-02T00:00:00Z")
        assertThat(source().fetch(reference()).sourceRevision).isNotEqualTo(first.sourceRevision)
        verify(exactly = 0) { client.fetchDirectBlockChildren(ROW) }
    }

    @Test
    fun `imports only the title when visible property settings are absent and revises the previous fallback`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = emptyList())
        val previous = source().fetch(reference())
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = null)
        every { client.fetchPage(ROW, listOf("title")) } returns row(ROW)

        val imported = source().fetch(reference())

        val data = dataSets(imported.content.roots.single()).single()
        assertThat(data.columns.map { it.name }).containsExactly("Name")
        assertThat(data.titleColumnIndex).isZero()
        assertThat(data.rows.single().cells.map(::text)).containsExactly("First")
        assertThat(data.toString()).doesNotContain("Hidden secret", "Status", "Done")
        assertThat(imported.sourceRevision).isNotEqualTo(previous.sourceRevision)
        assertThat(source().fetch(reference()).sourceRevision).isEqualTo(imported.sourceRevision)
        verify(exactly = 0) { client.fetchPage(ROW, listOf("title", "status")) }
    }

    @Test
    fun `does not restore explicitly empty columns or query a view without its source or layout`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = emptyList())
        assertThat(dataSets(source().fetch(reference()).content.roots.single())).isEmpty()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(dataSourceId = null, columns = null)
        assertThat(dataSets(source().fetch(reference()).content.roots.single())).isEmpty()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(configuration = null)
        assertThat(dataSets(source().fetch(reference()).content.roots.single())).isEmpty()
        verify(exactly = 0) { client.createViewQuery(any()) }
        verify(exactly = 0) { client.fetchDataSource(any()) }
    }

    @Test
    fun `rejects default schemas without a unique title rather than choosing another property`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = null)
        listOf(
            listOf(NotionDatabaseProperty("status", "Status", "status")),
            listOf(NotionDatabaseProperty("title", "Name", "title"), NotionDatabaseProperty("second-title", "Other", "title")),
            listOf(NotionDatabaseProperty("title", "Name", "title"), NotionDatabaseProperty("title", "Duplicate", "rich_text")),
        ).forEach { properties ->
            every { client.fetchDataSource(DATA_SOURCE) } returns NotionDataSourceResponse(DATA_SOURCE, properties, NotionParentResponse.Database(SECOND_VIEW), false)

            assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        }
        verify(exactly = 0) { client.createViewQuery(any()) }
    }

    @Test
    fun `keeps inaccessible display views as a safe link after ownership is checked`() {
        fixture()
        every { client.fetchDatabaseViews(DATABASE, null) } throws SourceAccessException()

        val database = source().fetch(reference()).content.roots.single()

        assertThat(dataSets(database)).isEmpty()
        assertThat((database.content as ReferenceBlockContent.DatabaseLink).originalUrl).isEqualTo(URI("https://www.notion.so/$DATABASE"))
    }

    @Test
    fun `propagates inaccessible database metadata and temporary failures`() {
        fixture()
        every { client.fetchDatabase(DATABASE) } throws SourceAccessException()
        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceAccessException::class.java)

        every { client.fetchDatabase(DATABASE) } throws RetryableSourceException()
        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `removes rows that became inaccessible without retaining their previous cells`() {
        fixture()
        val first = source().fetch(reference())
        every { client.fetchPage(ROW, listOf("title", "status")) } throws SourceAccessException()

        val second = source().fetch(reference())

        assertThat(dataSets(second.content.roots.single()).single().rows).isEmpty()
        assertThat(second.sourceRevision).isNotEqualTo(first.sourceRevision)
    }

    @Test
    fun `revises a snapshot when row values or visibility change while the parent revision stays the same`() {
        fixture()
        val first = source().fetch(reference())
        assertThat(source().fetch(reference()).sourceRevision).isEqualTo(first.sourceRevision)
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW, "Changed")

        val changed = source().fetch(reference())

        assertThat(changed.sourceRevision).isNotEqualTo(first.sourceRevision)
        assertThat(changed.sourceRevision.value).doesNotContain("Changed", "Hidden secret")
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW).copy(publicUrl = null)
        val unpublished = source().fetch(reference())
        assertThat(unpublished.sourceRevision).isNotEqualTo(changed.sourceRevision)
        assertThat(dataSets(unpublished.content.roots.single()).single().rows).isEmpty()
    }

    @Test
    fun `distinguishes column boundaries in empty data sets`() {
        fixture()
        every { client.createViewQuery(VIEW) } returns query(emptyList())
        every { client.fetchDatabaseView(VIEW) } returns view().copy(columns = listOf(NotionViewColumn("title", "Name, Status")))

        val singleColumn = source().fetch(reference())
        every { client.fetchDatabaseView(VIEW) } returns view()
        val twoColumns = source().fetch(reference())

        assertThat(dataSets(singleColumn.content.roots.single()).single().columns.map { it.name }).containsExactly("Name, Status")
        assertThat(dataSets(twoColumns.content.roots.single()).single().columns.map { it.name }).containsExactly("Name", "Status")
        assertThat(singleColumn.sourceRevision).isNotEqualTo(twoColumns.sourceRevision)
    }

    @Test
    fun `applies row column and cell budgets again when copying a synchronized database table`() {
        fixture()
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            NotionBlockEnvelope(SYNCED_ORIGIN, "synced_block", true, false, json.readTree("""{"synced_from":null}""")),
            NotionBlockEnvelope(SYNCED_REFERENCE, "synced_block", true, false, json.readTree("""{"synced_from":{"type":"block_id","block_id":"$SYNCED_ORIGIN"}}""")),
        )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns listOf(
            NotionBlockEnvelope(DATABASE, "child_database", true, false, json.readTree("""{"title":"Projects"}""")),
        )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns emptyList()

        val imported = source().fetch(reference())

        assertThat(imported.content.roots.flatMap(::dataSets)).hasSize(2)
        assertThatThrownBy { source(maxBlockCount = 15).fetch(reference()) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects foreign views and rows instead of rendering an unrelated source`() {
        fixture()
        every { client.fetchDatabaseView(VIEW) } returns view().copy(databaseId = DATA_SOURCE)
        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        every { client.fetchDatabaseView(VIEW) } returns view()
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW).copy(parent = NotionPageParentResponse("data_source_id", null, DATABASE))
        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects repeated query rows and pagination cursors instead of hanging or duplicating data`() {
        fixture()
        every { client.createViewQuery(VIEW) } returns query(listOf(ROW), "next")
        every { client.fetchViewQueryResults(VIEW, QUERY, "next") } returns pageOf(listOf(ROW))
        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        every { client.fetchViewQueryResults(VIEW, QUERY, "next") } returns pageOf(emptyList(), "next")
        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `treats an expired query as a failed import rather than publishing a partial table`() {
        fixture()
        every { client.createViewQuery(VIEW) } returns query(listOf(ROW), "next")
        every { client.fetchViewQueryResults(VIEW, QUERY, "next") } throws SourceAccessException()
        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `applies collection budgets and nesting limits to synthetic database tables`() {
        fixture()
        assertThatThrownBy { source(maxBlockCount = 3).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
        assertThatThrownBy { source(maxDepth = 2).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `checks the overall deadline between database requests`() {
        fixture()
        var expired = false
        every { client.fetchDatabase(DATABASE) } answers {
            expired = true
            database()
        }
        assertThatThrownBy { source(nanoTime = { if (expired) 2_000_000_000L else 0L }).fetch(reference()) }
            .isInstanceOf(RetryableSourceException::class.java)
        verify(exactly = 0) { client.fetchDatabaseViews(any(), any()) }
    }

    @Test
    fun `does not import a database that is only linked in the page body`() {
        fixture()
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            NotionBlockEnvelope("link", "link_to_page", false, false, json.readTree("""{"type":"database_id","database_id":"$DATABASE"}""")),
        )
        val imported = source().fetch(reference())
        assertThat(imported.content.roots.single().content).isInstanceOf(ReferenceBlockContent.DatabaseLink::class.java)
        assertThat(imported.content.roots.single().children).isEmpty()
        verify(exactly = 0) { client.fetchDatabase(any()) }
    }

    private fun fixture() {
        every { client.fetchPage(ROOT) } returns NotionPageResponse(
            ROOT,
            NotionPageParentResponse("workspace", null),
            "https://www.notion.so/$ROOT",
            "https://site.notion.site/$ROOT",
            false,
            "2026-08-31T00:00:00Z",
            json.readTree("""{"Name":{"type":"title","title":[{"plain_text":"Root"}]}}"""),
        )
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(NotionBlockEnvelope(DATABASE, "child_database", true, false, json.readTree("""{"title":"Projects"}""")))
        every { client.fetchDatabase(DATABASE) } returns database()
        every { client.fetchDatabaseViews(DATABASE, null) } returns pageOf(listOf(VIEW))
        every { client.fetchDatabaseView(VIEW) } returns view()
        every { client.fetchDataSource(DATA_SOURCE) } returns NotionDataSourceResponse(
            DATA_SOURCE,
            listOf(
                NotionDatabaseProperty("secret", "Hidden", "rich_text"),
                NotionDatabaseProperty("status", "Status", "status"),
                NotionDatabaseProperty("title", "Name", "title"),
            ),
            NotionParentResponse.Database(SECOND_VIEW),
            false,
        )
        every { client.createViewQuery(VIEW) } returns query(listOf(ROW))
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW)
    }

    private fun database() = NotionDatabaseResponse(DATABASE, "Projects", "https://www.notion.so/$DATABASE", false, NotionParentResponse.Workspace, emptyList())

    private fun view(id: String = VIEW) = NotionDatabaseViewResponse(
        id,
        DATABASE,
        "Published projects",
        "table",
        DATA_SOURCE,
        listOf(NotionViewColumn("title", null), NotionViewColumn("status", null)),
        configuration = NotionViewConfiguration.Table(),
    )

    private fun query(ids: List<String>, cursor: String? = null, viewId: String = VIEW) = NotionViewQueryResponse(QUERY, viewId, pageOf(ids, cursor))

    private fun pageOf(ids: List<String>, cursor: String? = null) = NotionPaginationResponse(ids, cursor != null, cursor)

    private fun row(id: String, name: String = "First") = NotionPageResponse(
        id,
        NotionPageParentResponse("data_source_id", null, DATA_SOURCE),
        "https://www.notion.so/$id",
        "https://site.notion.site/$id",
        false,
        "2026-08-31T00:00:00Z",
        json.readTree(
            """{
          "Old name":{"id":"title","type":"title","title":[{"type":"text","text":{"content":"$name"},"plain_text":"$name","annotations":{"bold":false,"italic":false,"strikethrough":false,"underline":false,"code":false,"color":"default"}}]},
          "State":{"id":"status","type":"status","status":{"name":"Done","color":"green"}},
          "Hidden":{"id":"secret","type":"rich_text","rich_text":[{"type":"text","text":{"content":"Hidden secret"}}]}
        }""",
        ),
    )

    private fun reference() = SourceDocumentRef(sourceId, ROOT)

    private fun source(maxBlockCount: Int = 100, maxDepth: Int = 8, nanoTime: () -> Long = System::nanoTime) = NotionPostSource(
        sourceId,
        client,
        maxDepth = maxDepth,
        maxBlockCount = maxBlockCount,
        collectionTimeout = Duration.ofSeconds(1),
        nanoTime = nanoTime,
    )

    private fun nodes(node: BlockNode): List<BlockNode> = listOf(node) + node.children.flatMap(::nodes)

    private fun dataViews(node: BlockNode): List<DataViewContent> = nodes(node).mapNotNull { it.content as? DataViewContent }

    private fun dataSets(node: BlockNode): List<DataSet> = dataViews(node).map { it.data }

    private fun text(cell: List<InlineContent>): String = cell.joinToString("") { (it as InlineContent.Text).text }

    private companion object {
        const val ROOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATABASE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val VIEW = "cccccccccccccccccccccccccccccccc"
        const val SECOND_VIEW = "dddddddddddddddddddddddddddddddd"
        const val DATA_SOURCE = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val QUERY = "ffffffffffffffffffffffffffffffff"
        const val ROW = "11111111111111111111111111111111"
        const val SECOND_ROW = "22222222222222222222222222222222"
        const val PRIVATE_ROW = "33333333333333333333333333333333"
        const val TRASH_ROW = "44444444444444444444444444444444"
        const val SYNCED_ORIGIN = "55555555555555555555555555555555"
        const val SYNCED_REFERENCE = "66666666666666666666666666666666"
    }
}
