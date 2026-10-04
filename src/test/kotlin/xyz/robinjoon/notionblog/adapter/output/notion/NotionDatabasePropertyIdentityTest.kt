package xyz.robinjoon.notionblog.adapter.output.notion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewQueryResponse
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionBlockMapper
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI

class NotionDatabasePropertyIdentityTest {
    private val client = mockk<NotionApiClient>()
    private val sourceId = SourceId("notion-main")
    private val json = JsonMapper.builder().build()
    private val reader = NotionInlineDatabaseReader(client, NotionBlockMapper(sourceId), sourceId, 8)

    @Test
    fun `matches raw saved view property ids to encoded schema ids without changing row request ids`() {
        fixture(
            listOf(
                NotionDatabaseProperty("r%5CcN", "Tags", "multi_select"),
                NotionDatabaseProperty("j%3CW%5D", "Date", "date"),
                NotionDatabaseProperty("title", "Name", "title"),
            ),
            listOf("r\\cN", "j<W]", "title"),
        )

        val data = readView().data

        assertThat(data.columns.map { it.name }).containsExactly("Tags", "Date", "Name")
        assertThat(data.rows.single().cells.map(::text)).containsExactly("Selected tag", "2026-10-05", "Article")
        verify(exactly = 1) { client.fetchPage(ROW, listOf("r%5CcN", "j%3CW%5D", "title")) }
    }

    @ParameterizedTest
    @CsvSource(
        "r%5CcN,r%5CcN",
        "r%5CcN,r\\cN",
        "x+%5Cy,x+\\y",
        "a%252F,a%2F",
        "a%252F,a%252F",
    )
    fun `matches either schema spelling or one decoded spelling while preserving literal plus`(wireId: String, viewId: String) {
        fixture(listOf(NotionDatabaseProperty(wireId, "Text", "rich_text")), listOf(viewId))

        assertThat(readView().data.rows.single().cells.single()).containsExactly(InlineContent.Text("Property value"))
        verify(exactly = 1) { client.fetchPage(ROW, listOf(wireId)) }
    }

    @ParameterizedTest
    @CsvSource(
        "a%252F,a/",
        "a/,a%2F",
        "x+y,x y",
    )
    fun `rejects double decoding and does not decode the saved view id or convert plus to a space`(wireId: String, viewId: String) {
        fixture(listOf(NotionDatabaseProperty(wireId, "Text", "rich_text")), listOf(viewId))

        assertThatThrownBy { readView() }.isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.createViewQuery(any()) }
    }

    @Test
    fun `rejects a view id that matches one schema id exactly and another after decoding`() {
        fixture(
            listOf(NotionDatabaseProperty("a%252F", "First", "rich_text"), NotionDatabaseProperty("a%2F", "Second", "rich_text")),
            listOf("a%2F"),
        )

        assertThatThrownBy { readView() }.isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.createViewQuery(any()) }
    }

    @Test
    fun `rejects a decoded alias shared by differently encoded schema ids`() {
        fixture(
            listOf(NotionDatabaseProperty("a%2F", "First", "rich_text"), NotionDatabaseProperty("a%2f", "Second", "rich_text")),
            listOf("a/"),
        )

        assertThatThrownBy { readView() }.isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.createViewQuery(any()) }
    }

    @Test
    fun `rejects two view spellings that select the same schema property`() {
        fixture(listOf(NotionDatabaseProperty("a%2F", "Text", "rich_text")), listOf("a/", "a%2F"))

        assertThatThrownBy { readView() }.isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.createViewQuery(any()) }
    }

    @Test
    fun `rejects an unknown property even if its value equals a display name`() {
        fixture(listOf(NotionDatabaseProperty("actual", "Display name", "rich_text")), listOf("Display name"))

        assertThatThrownBy { readView() }.isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.createViewQuery(any()) }
    }

    @Test
    fun `resolves a raw gallery cover property to the encoded request and row lookup id`() {
        fixture(
            listOf(NotionDatabaseProperty("title", "Name", "title"), NotionDatabaseProperty("p%5EqJ", "Artwork", "files")),
            listOf("title"),
            NotionViewConfiguration.Gallery(cover = NotionGalleryCover.Property("p^qJ")),
        )

        val data = readView().data

        assertThat(data.columns.map { it.name }).containsExactly("Name")
        assertThat(data.rows.single().cover).isEqualTo(MediaSource.External(URI(COVER)))
        assertThat(data.rows.single().cells.toString()).doesNotContain("Artwork", COVER)
        verify(exactly = 1) { client.fetchPage(ROW, listOf("title", "p%5EqJ")) }
    }

    @Test
    fun `requests a property only once when a visible column and gallery cover use different spellings`() {
        fixture(
            listOf(NotionDatabaseProperty("title", "Name", "title"), NotionDatabaseProperty("p%5EqJ", "Artwork", "files")),
            listOf("title", "p^qJ"),
            NotionViewConfiguration.Gallery(cover = NotionGalleryCover.Property("p%5EqJ")),
        )

        val row = readView().data.rows.single()

        assertThat(row.cells.map(::text)).containsExactly("Article", "cover.png")
        assertThat(row.cover).isEqualTo(MediaSource.External(URI(COVER)))
        verify(exactly = 1) { client.fetchPage(ROW, listOf("title", "p%5EqJ")) }
    }

    @Test
    fun `rejects an ambiguous cover property before fetching any displayed row`() {
        fixture(
            listOf(
                NotionDatabaseProperty("title", "Name", "title"),
                NotionDatabaseProperty("a%252F", "First artwork", "files"),
                NotionDatabaseProperty("a%2F", "Second artwork", "files"),
            ),
            listOf("title"),
            NotionViewConfiguration.Gallery(cover = NotionGalleryCover.Property("a%2F")),
        )

        assertThatThrownBy { readView() }.isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.createViewQuery(any()) }
    }

    private fun fixture(
        properties: List<NotionDatabaseProperty>,
        columnIds: List<String>,
        configuration: NotionViewConfiguration = NotionViewConfiguration.Table(),
    ) {
        every { client.fetchDatabase(DATABASE) } returns NotionDatabaseResponse(DATABASE, "Posts", null, false, NotionParentResponse.Workspace, emptyList())
        every { client.fetchDatabaseViews(DATABASE, null) } returns NotionPaginationResponse(listOf(VIEW), false, null)
        every { client.fetchDatabaseView(VIEW) } returns NotionDatabaseViewResponse(
            VIEW,
            DATABASE,
            "Posts",
            if (configuration is NotionViewConfiguration.Gallery) "gallery" else "table",
            DATA_SOURCE,
            columnIds.map { NotionViewColumn(it, null) },
            configuration,
        )
        every { client.fetchDataSource(DATA_SOURCE) } returns NotionDataSourceResponse(DATA_SOURCE, properties, NotionParentResponse.Database(DATABASE), false)
        every { client.createViewQuery(VIEW) } returns NotionViewQueryResponse(QUERY, VIEW, NotionPaginationResponse(listOf(ROW), false, null))
        val fields = properties.mapIndexed { index, property ->
            val value: Any = when (property.type) {
                "title" -> listOf(mapOf("type" to "text", "text" to mapOf("content" to "Article"), "plain_text" to "Article", "annotations" to emptyMap<String, String>()))
                "multi_select" -> listOf(mapOf("name" to "Selected tag"))
                "date" -> mapOf("start" to "2026-10-05", "end" to null)
                "files" -> listOf(mapOf("name" to "cover.png", "type" to "external", "external" to mapOf("url" to COVER)))
                else -> listOf(mapOf("type" to "text", "text" to mapOf("content" to "Property value"), "plain_text" to "Property value", "annotations" to emptyMap<String, String>()))
            }
            "field-$index" to mapOf("id" to property.id, "type" to property.type, property.type to value)
        }.toMap()
        every { client.fetchPage(ROW, any()) } returns NotionPageResponse(
            ROW,
            NotionPageParentResponse("data_source_id", null, DATA_SOURCE),
            "https://www.notion.so/$ROW",
            "https://site.notion.site/$ROW",
            false,
            "2026-10-05T00:00:00Z",
            json.readTree(json.writeValueAsString(fields)),
        )
    }

    private fun readView(): DataViewContent {
        val block = BlockNode(BlockId(DATABASE), ReferenceBlockContent.DatabaseLink(SourceDocumentRef(sourceId, DATABASE), null, "Posts"))
        val result = reader.read(block, SourceDocumentRef(sourceId, ROOT), checkDeadline = {}, reserve = {})
        return checkNotNull(result.block).let(::nodes).mapNotNull { it.content as? DataViewContent }.single()
    }

    private fun nodes(node: BlockNode): List<BlockNode> = listOf(node) + node.children.flatMap(::nodes)

    private fun text(cell: List<InlineContent>): String = cell.joinToString("") { (it as InlineContent.Text).text }

    private companion object {
        const val ROOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATABASE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DATA_SOURCE = "cccccccccccccccccccccccccccccccc"
        const val VIEW = "dddddddddddddddddddddddddddddddd"
        const val QUERY = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val ROW = "ffffffffffffffffffffffffffffffff"
        const val COVER = "https://images.example/cover.png"
    }
}
