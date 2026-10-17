package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.DATABASE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.DATA_SOURCE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.PRIVATE_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.SECOND_VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.TRASH_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.dataSets
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.json
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.query
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.row
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.source
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.stubDefaults
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionInlineDatabaseFixture.view
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.net.URI
import java.time.Instant

class NotionInlineDatabaseRowDisplayTest {
    private val client = mockk<NotionApiClient>()

    @Test
    fun `rejects a gallery row whose parent is not the view data source`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns
            view().copy(
                type = "gallery",
                configuration = NotionViewConfiguration.Gallery(cover = NotionGalleryCover.PageContent),
            )
        every { client.createViewQuery(VIEW) } returns query(listOf(PRIVATE_ROW, TRASH_ROW, ROW))
        every { client.fetchPage(PRIVATE_ROW, listOf("title", "status")) } returns row(PRIVATE_ROW).copy(publicUrl = null)
        every { client.fetchPage(TRASH_ROW, listOf("title", "status")) } returns row(TRASH_ROW).copy(inTrash = true)
        every { client.fetchBlockChildrenPage(PRIVATE_ROW, null) } returns NotionPaginationResponse(emptyList(), false, null)
        every { client.fetchPage(ROW, listOf("title", "status")) } returns
            row(ROW).copy(parent = NotionPageParentResponse("data_source_id", null, DATABASE))

        assertThatThrownBy { source(client).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `uses row icons for lists and galleries but renders covers only for configured galleries`() {
        stubDefaults(client)
        every { client.fetchPage(ROW, listOf("title", "status")) } returns
            row(ROW).copy(
                icon = json.readTree("""{"type":"emoji","emoji":"📚"}"""),
                cover = json.readTree("""{"type":"external","external":{"url":"https://images.example/cover.png"}}"""),
            )

        val table =
            dataSets(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ).single()
        assertThat(table.rows.single().icon).isNull()
        assertThat(table.rows.single().cover).isNull()

        every { client.fetchDatabaseView(VIEW) } returns view().copy(type = "list", configuration = NotionViewConfiguration.ListView)
        val list =
            dataSets(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ).single()
        assertThat(list.rows.single().icon).isEqualTo(BlockIcon.Emoji("📚"))
        assertThat(list.rows.single().cover).isNull()

        every { client.fetchDatabaseView(VIEW) } returns
            view().copy(type = "gallery", configuration = NotionViewConfiguration.Gallery(cover = NotionGalleryCover.PageCover))
        val gallery =
            dataSets(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ).single()
        assertThat(gallery.rows.single().icon).isEqualTo(BlockIcon.Emoji("📚"))
        assertThat(gallery.rows.single().cover).isEqualTo(MediaSource.External(URI("https://images.example/cover.png")))
    }

    @Test
    fun `applies the parent collection budget to all returned cover blocks before choosing the first image`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns
            view().copy(type = "gallery", configuration = NotionViewConfiguration.Gallery(cover = NotionGalleryCover.PageContent))
        val image =
            NotionBlockEnvelope(
                "cover",
                "image",
                false,
                false,
                json.readTree("""{"type":"external","external":{"url":"https://images.example/cover.png"}}"""),
            )
        val blocks =
            listOf(image) +
                (1..20).map { index ->
                    NotionBlockEnvelope("discarded-$index", "paragraph", false, true, json.readTree("""{"rich_text":[]}"""))
                }
        every { client.fetchBlockChildrenPage(ROW, null) } returns NotionPaginationResponse(blocks, true, "unused-next")

        assertThat(
            dataSets(
                source(client)
                    .fetch(reference())
                    .content.roots
                    .single(),
            ).single().rows.single().cover,
        ).isEqualTo(MediaSource.External(URI("https://images.example/cover.png")))
        assertThatThrownBy { source(client, maxBlockCount = 12).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `reads an explicit hidden cover property without exposing it as a cell and retains signed expiry`() {
        stubDefaults(client)
        every { client.fetchDatabaseView(VIEW) } returns
            view().copy(
                type = "gallery",
                configuration = NotionViewConfiguration.Gallery(cover = NotionGalleryCover.Property("art")),
            )
        every { client.fetchDataSource(DATA_SOURCE) } returns
            NotionDataSourceResponse(
                DATA_SOURCE,
                listOf(
                    NotionDatabaseProperty("title", "Name", "title"),
                    NotionDatabaseProperty("status", "Status", "status"),
                    NotionDatabaseProperty("art", "Hidden art", "files"),
                ),
                NotionParentResponse.Database(SECOND_VIEW),
                false,
            )

        fun covered(expiry: String) =
            row(ROW).copy(
                properties =
                    json.readTree(
                        row(ROW).properties.toString().dropLast(1) +
                            """, "Hidden art":{"id":"art","type":"files","files":[{"name":"cover.png","type":"file",
                              "file":{"url":"https://files.example/cover.png?signature=test","expiry_time":"$expiry"}}]}}""",
                    ),
            )
        every { client.fetchPage(ROW, listOf("title", "status", "art")) } returns covered("2026-09-01T00:00:00Z")

        val first = source(client).fetch(reference())
        val data = dataSets(first.content.roots.single()).single()

        assertThat(data.columns.map { it.name }).containsExactly("Name", "Status")
        assertThat(data.rows.single().cells).hasSize(2)
        assertThat(
            data.rows.single().cover,
        ).isEqualTo(MediaSource.SourceHosted(URI("https://files.example/cover.png?signature=test"), Instant.parse("2026-09-01T00:00:00Z")))
        assertThat(
            data.rows
                .single()
                .cells
                .toString(),
        ).doesNotContain("Hidden art", "cover.png")
        every { client.fetchPage(ROW, listOf("title", "status", "art")) } returns covered("2026-09-02T00:00:00Z")
        assertThat(source(client).fetch(reference()).sourceRevision).isNotEqualTo(first.sourceRevision)
    }
}
