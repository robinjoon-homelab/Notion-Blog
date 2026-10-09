package xyz.robinjoon.notionblog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionBlockMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionBlockMappingException
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

class NotionDataViewCoverReaderTest {
    private val json = JsonMapper.builder().build()
    private val client = mockk<NotionApiClient>()
    private val reader = NotionDataViewCoverReader(client, NotionBlockMapper(SourceId("notion-main")))

    @Test
    fun `keeps the configured page cover source and hosted expiry without reading page content`() {
        val external = page().copy(cover = json.readTree(external("https://images.example/cover.png")))
        val hosted = page().copy(cover = json.readTree(hosted("https://images.example/cover.png")))

        assertThat(read(external, NotionGalleryCover.PageCover))
            .isEqualTo(MediaSource.External(URI("https://images.example/cover.png")))
        assertThat(read(hosted, NotionGalleryCover.PageCover))
            .isEqualTo(MediaSource.SourceHosted(URI("https://images.example/cover.png"), expiry))
        verify(exactly = 0) { client.fetchBlockChildrenPage(any(), any()) }
    }

    @Test
    fun `missing or unconfigured covers remain absent rather than using another image`() {
        val row = page().copy(cover = json.readTree(external("https://images.example/cover.png")))

        assertThat(read(row, null)).isNull()
        assertThat(read(page(), NotionGalleryCover.PageCover)).isNull()
        assertThat(read(page().copy(cover = json.readTree("null")), NotionGalleryCover.PageCover)).isNull()
        verify(exactly = 0) { client.fetchBlockChildrenPage(any(), any()) }
    }

    @Test
    fun `chooses the first identifiable image from the stable files property id`() {
        val row =
            page(
                """{
                "Renamed cover":{"id":"cover-id","type":"files","files":[
                    ${external("https://files.example/report.pdf", "report.pdf")},
                    ${hosted("https://files.example/download?signature=example", "preview.WEBP")},
                    ${external("https://files.example/later.png", "later.png")}
                ]},
                "cover-id":{"id":"hidden-id","type":"files","files":[
                    ${external("https://files.example/hidden.png", "hidden.png")}
                ]}
            }""",
            )

        assertThat(read(row, NotionGalleryCover.Property("cover-id")))
            .isEqualTo(MediaSource.SourceHosted(URI("https://files.example/download?signature=example"), expiry))
        verify(exactly = 0) { client.fetchBlockChildrenPage(any(), any()) }
    }

    @Test
    fun `recognizes image URLs with query strings but does not treat documents as images`() {
        val image =
            page(
                """{"Cover":{"id":"cover-id","type":"files","files":[${external(
                    "https://files.example/photo.AVIF?width=500",
                    "download",
                )}]}}""",
            )
        val document =
            page("""{"Cover":{"id":"cover-id","type":"files","files":[${external("https://files.example/manual.pdf", "Manual")}]}}""")

        assertThat(read(image, NotionGalleryCover.Property("cover-id")))
            .isEqualTo(MediaSource.External(URI("https://files.example/photo.AVIF?width=500")))
        assertThat(read(document, NotionGalleryCover.Property("cover-id"))).isNull()
        assertThat(read(page(), NotionGalleryCover.Property("cover-id"))).isNull()
        assertThat(
            read(
                page("""{"Cover":{"id":"cover-id","type":"files","files":[],"has_more":true}}"""),
                NotionGalleryCover.Property("cover-id"),
            ),
        ).isNull()
    }

    @Test
    fun `rejects malformed or duplicated selected files properties`() {
        val properties =
            listOf(
                """{"Cover":{"id":"cover-id","type":"url","url":"https://files.example/photo.png"}}""",
                """{"Cover":{"id":"cover-id","type":"files","files":{}}}""",
                """{"Cover":{"id":"cover-id","type":"files","files":[42]}}""",
                """{"Cover":{"id":"cover-id","type":"files","files":[{"name":"cover.png","type":"external"}]}}""",
                """{"Cover":{"id":"cover-id","type":"files","files":[{"name":"cover.png","type":"file",
                  "file":{"url":"https://files.example/cover.png","expiry_time":42}}]}}""",
                """{"Cover":{"id":"cover-id","type":"files","files":[],"has_more":"yes"}}""",
                """{"A":{"id":"cover-id","type":"files","files":[]},"B":{"id":"cover-id","type":"files","files":[]}}""",
            )

        properties.forEach { propertiesJson ->
            assertThatThrownBy { read(page(propertiesJson), NotionGalleryCover.Property("cover-id")) }
                .isInstanceOf(NotionBlockMappingException::class.java)
        }
    }

    @Test
    fun `uses only the first direct nontrashed image and never follows nested or linked content`() {
        val blocks =
            listOf(
                block("paragraph", """{"rich_text":[]}""", children = true),
                block("child_page", """{"title":"Nested page"}""", children = true),
                block("child_database", """{"title":"Nested database"}""", children = true),
                block("synced_block", """{"synced_from":{"type":"block_id","block_id":"original"}}""", children = true),
                block("image", external("https://images.example/trashed.png"), trash = true),
                block("image", hosted("https://images.example/first.png")),
                block("image", external("https://images.example/later.png")),
            )
        every { client.fetchBlockChildrenPage(ROW, null) } returns pageOf(blocks, "next-page")
        var reservations = 0

        val cover = reader.read(page(), NotionGalleryCover.PageContent, {}, { reservations += 1 })

        assertThat(cover).isEqualTo(MediaSource.SourceHosted(URI("https://images.example/first.png"), expiry))
        assertThat(reservations).isEqualTo(blocks.size)
        verify(exactly = 1) { client.fetchBlockChildrenPage(ROW, null) }
        verify(exactly = 1) { client.fetchBlockChildrenPage(any(), any()) }
        verify(exactly = 0) { client.fetchDirectBlockChildren(any()) }
    }

    @Test
    fun `finds an image on later direct pages and rejects repeated pagination cursors`() {
        every { client.fetchBlockChildrenPage(ROW, null) } returns pageOf(listOf(block("paragraph", """{"rich_text":[]}""")), "next")
        every { client.fetchBlockChildrenPage(ROW, "next") } returns
            pageOf(listOf(block("image", external("https://images.example/later.png"))))

        assertThat(read(page(), NotionGalleryCover.PageContent)).isEqualTo(MediaSource.External(URI("https://images.example/later.png")))

        every { client.fetchBlockChildrenPage(ROW, "next") } returns pageOf(emptyList(), "next")
        assertThatThrownBy { read(page(), NotionGalleryCover.PageContent) }.isInstanceOf(NotionBlockMappingException::class.java)

        every { client.fetchBlockChildrenPage(ROW, "next") } returns pageOf(emptyList())
        assertThat(read(page(), NotionGalleryCover.PageContent)).isNull()
    }

    @Test
    fun `returns no content cover for inaccessible content but propagates temporary and authentication failures`() {
        every { client.fetchBlockChildrenPage(ROW, null) } throws SourceAccessException()
        assertThat(read(page(), NotionGalleryCover.PageContent)).isNull()

        every { client.fetchBlockChildrenPage(ROW, null) } throws RetryableSourceException()
        assertThatThrownBy { read(page(), NotionGalleryCover.PageContent) }.isInstanceOf(RetryableSourceException::class.java)

        every { client.fetchBlockChildrenPage(ROW, null) } throws SourceAuthenticationException()
        assertThatThrownBy { read(page(), NotionGalleryCover.PageContent) }.isInstanceOf(SourceAuthenticationException::class.java)
    }

    @Test
    fun `applies the enclosing collection budget and deadline to cover reads`() {
        every { client.fetchBlockChildrenPage(ROW, null) } returns
            pageOf(listOf(block("image", external("https://images.example/cover.png"))))
        assertThatThrownBy {
            reader.read(page(), NotionGalleryCover.PageContent, {}, { throw NotionBlockMappingException("budget reached") })
        }.isInstanceOf(NotionBlockMappingException::class.java)

        assertThatThrownBy {
            reader.read(page(), NotionGalleryCover.PageContent, { throw RetryableSourceException() }, {})
        }.isInstanceOf(RetryableSourceException::class.java)

        val row =
            page("""{"Cover":{"id":"cover-id","type":"files","files":[${external("https://images.example/cover.png", "cover.png")}]}}""")
        assertThatThrownBy {
            reader.read(row, NotionGalleryCover.Property("cover-id"), {}, { throw NotionBlockMappingException("budget reached") })
        }.isInstanceOf(NotionBlockMappingException::class.java)
    }

    @Test
    fun `rejects unsafe or malformed cover URLs without fetching alternate content`() {
        listOf(
            "javascript:alert(1)",
            "https:opaque",
            "https://user:password@images.example/cover.png",
            "https://bad host/cover.png",
        ).forEach { url ->
            assertThatThrownBy { read(page().copy(cover = json.readTree(external(url))), NotionGalleryCover.PageCover) }
                .isInstanceOf(NotionBlockMappingException::class.java)
        }
        verify(exactly = 0) { client.fetchBlockChildrenPage(any(), any()) }
    }

    @Test
    fun `preserves malformed media causes without exposing the URL in mapping messages`() {
        val payload = json.readTree(external("https://bad host/cover.png?signature=private-value"))

        assertThatThrownBy { read(page().copy(cover = payload), NotionGalleryCover.PageCover) }
            .isInstanceOf(NotionBlockMappingException::class.java)
            .hasCauseInstanceOf(URISyntaxException::class.java)
            .hasMessage("URL is invalid")
        assertThatThrownBy { reader.icon(page().copy(icon = payload)) }
            .isInstanceOf(NotionBlockMappingException::class.java)
            .hasCauseInstanceOf(URISyntaxException::class.java)
            .hasMessage("URL is invalid")
    }

    @Test
    fun `reuses typed icon mapping while rejecting unsafe media icons`() {
        assertThat(reader.icon(page().copy(icon = json.readTree("""{"type":"emoji","emoji":"📚"}"""))))
            .isEqualTo(BlockIcon.Emoji("📚"))
        assertThat(reader.icon(page().copy(icon = json.readTree(hosted("https://images.example/icon.png")))))
            .isEqualTo(BlockIcon.Media(MediaSource.SourceHosted(URI("https://images.example/icon.png"), expiry)))
        assertThat(reader.icon(page())).isNull()
        assertThatThrownBy { reader.icon(page().copy(icon = json.readTree(external("https://user@images.example/icon.png")))) }
            .isInstanceOf(NotionBlockMappingException::class.java)
    }

    @Test
    fun `rejects nontext and blank hosted expiry rather than treating covers or icons as permanent`() {
        listOf("42", "\"\"", "\" \"").forEach { expiry ->
            val media = json.readTree("""{"type":"file","file":{"url":"https://images.example/cover.png","expiry_time":$expiry}}""")
            assertThatThrownBy { read(page().copy(cover = media), NotionGalleryCover.PageCover) }
                .isInstanceOf(NotionBlockMappingException::class.java)
            assertThatThrownBy { reader.icon(page().copy(icon = media)) }
                .isInstanceOf(NotionBlockMappingException::class.java)
        }
    }

    private fun read(
        page: NotionPageResponse,
        cover: NotionGalleryCover?,
    ): MediaSource? = reader.read(page, cover, {}, {})

    private fun page(properties: String = "{}"): NotionPageResponse =
        NotionPageResponse(
            id = ROW,
            parent = NotionPageParentResponse("data_source_id", null, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
            url = "https://www.notion.so/$ROW",
            publicUrl = "https://blog.notion.site/$ROW",
            inTrash = false,
            lastEditedTime = "2026-08-31T00:00:00Z",
            properties = json.readTree(properties),
        )

    private fun block(
        type: String,
        payload: String,
        children: Boolean = false,
        trash: Boolean = false,
    ) = NotionBlockEnvelope(
        id = "block-$type",
        type = type,
        hasChildren = children,
        inTrash = trash,
        payload = json.readTree(payload),
    )

    private fun pageOf(
        blocks: List<NotionBlockEnvelope>,
        nextCursor: String? = null,
    ) = NotionPaginationResponse(
        results = blocks,
        hasMore = nextCursor != null,
        nextCursor = nextCursor,
    )

    private fun external(
        url: String,
        name: String? = null,
    ): String = """{"type":"external","external":{"url":"$url"}${name?.let { ",\"name\":\"$it\"" }.orEmpty()}}"""

    private fun hosted(
        url: String,
        name: String? = null,
    ): String = """{"type":"file","file":{"url":"$url","expiry_time":"$expiry"}${name?.let { ",\"name\":\"$it\"" }.orEmpty()}}"""

    private companion object {
        const val ROW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val expiry: Instant = Instant.parse("2026-09-01T00:00:00Z")
    }
}
