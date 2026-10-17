package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.blog.domain.post.block.content.NumberedListFormat
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken
import java.net.URI
import java.time.Instant

class NotionBlockMapperTest {
    private val fixture = NotionBlockMapperFixture()
    private val mapper = fixture.mapper

    @Test
    fun `recognizes every official 2026-03-11 block response alternative`() {
        val fixture = fixture.objectMapper.readTree(readResource("notion/2026-03-11/block-alternatives.json"))
        val alternatives = fixture.toList()
        val mapped =
            alternatives.map { entry ->
                mapper.map(
                    NotionBlockEnvelope(
                        id = entry.required("id").stringValue(),
                        type = entry.required("type").stringValue(),
                        hasChildren = entry.required("has_children").asBoolean(),
                        inTrash = false,
                        payload = entry.required("payload"),
                    ),
                )
            }

        assertThat(mapped).hasSize(36)
        assertThat(mapped.map { it.content::class.simpleName }).containsExactlyElementsOf(
            alternatives.map { it.required("expected").stringValue() },
        )
    }

    @Test
    fun `maps source hosted media expiry and external media type`() {
        val hosted =
            mapper
                .map(
                    fixture.envelope(
                        type = "image",
                        payload =
                            """
                            {"type":"file","file":{"url":"https://secure.notion-static.com/image.png",
                              "expiry_time":"2026-08-25T12:30:00Z"},"caption":[]}
                            """.trimIndent(),
                    ),
                ).content as MediaBlockContent.Media
        val external =
            mapper
                .map(
                    fixture.envelope(
                        type = "audio",
                        payload = """{"type":"external","external":{"url":"https://example.com/audio.mp3"},"caption":[]}""",
                    ),
                ).content as MediaBlockContent.Media

        assertThat(hosted.mediaType).isEqualTo(MediaType.IMAGE)
        assertThat(hosted.source).isEqualTo(
            MediaSource.SourceHosted(
                java.net.URI("https://secure.notion-static.com/image.png"),
                Instant.parse("2026-08-25T12:30:00Z"),
            ),
        )
        assertThat(external.mediaType).isEqualTo(MediaType.AUDIO)
        assertThat(external.source).isEqualTo(MediaSource.External(java.net.URI("https://example.com/audio.mp3")))
    }

    @Test
    fun `normalizes tab paragraph children to tab items and preserves their nested content`() {
        val tabChild =
            BlockNode(
                id = BlockId("tab-title"),
                content = TextBlockContent.Paragraph(listOf(InlineContent.Text("Overview"))),
                children =
                    listOf(
                        BlockNode(
                            id = BlockId("tab-body"),
                            content = TextBlockContent.Paragraph(listOf(InlineContent.Text("Body"))),
                        ),
                    ),
            )

        val mapped = mapper.map(fixture.envelope(type = "tab", payload = "{}"), listOf(tabChild))

        assertThat(mapped.content).isEqualTo(LayoutBlockContent.TabContainer)
        assertThat(mapped.children.single().content).isEqualTo(
            LayoutBlockContent.TabItem(listOf(InlineContent.Text("Overview")), null),
        )
        assertThat(
            mapped.children
                .single()
                .children
                .single()
                .id,
        ).isEqualTo(BlockId("tab-body"))
    }

    @Test
    fun `maps a tab paragraph's title and icon when its caller supplies tab context`() {
        val tabItem =
            mapper.mapTabItem(
                fixture.envelope(
                    type = "paragraph",
                    payload =
                        """
                        {"rich_text":[{"type":"text","text":{"content":"Overview","link":null},"annotations":{"color":"default"},
                          "plain_text":"Overview","href":null}],"icon":{"emoji":"📋"}}
                        """.trimIndent(),
                ),
            )

        assertThat(tabItem.content).isEqualTo(
            LayoutBlockContent.TabItem(
                title = listOf(InlineContent.Text("Overview")),
                icon =
                    xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
                        .Emoji("📋"),
            ),
        )
    }

    @Test
    fun `maps numbered list start format and explicit restart marker`() {
        val variants =
            listOf(
                Triple("numbers", NumberedListFormat.DECIMAL, 1),
                Triple("letters", NumberedListFormat.LOWER_ALPHA, 4),
                Triple("roman", NumberedListFormat.LOWER_ROMAN, 5),
            )

        variants.forEach { (notionFormat, expectedFormat, startNumber) ->
            val content =
                mapper
                    .map(
                        fixture.envelope(
                            type = "numbered_list_item",
                            payload = """{"rich_text":[],"list_start_index":$startNumber,"list_format":"$notionFormat"}""",
                        ),
                    ).content as ListBlockContent.NumberedItem

            assertThat(content.startNumber).isEqualTo(startNumber)
            assertThat(content.displayFormat).isEqualTo(expectedFormat)
            assertThat(content.startsNewList).isTrue()
        }

        assertThat(
            mapper.map(fixture.envelope(type = "numbered_list_item", payload = """{"rich_text":[]}""")).content,
        ).isEqualTo(ListBlockContent.NumberedItem(emptyList()))
    }

    @Test
    fun `rejects an unknown numbered list format instead of silently changing its meaning`() {
        assertThatThrownBy {
            mapper.map(
                fixture.envelope(
                    type = "numbered_list_item",
                    payload = """{"rich_text":[],"list_format":"future_format"}""",
                ),
            )
        }.isInstanceOf(NotionBlockMappingException::class.java)
    }

    @Test
    fun `preserves child database title and template rich text title`() {
        val database =
            mapper
                .map(
                    fixture.envelope(
                        id = "database-external-id",
                        type = "child_database",
                        payload = """{"title":"Team projects"}""",
                    ),
                ).content as ReferenceBlockContent.DatabaseLink
        val template =
            mapper
                .map(
                    fixture.envelope(
                        type = "template",
                        payload = richTextPayload("Weekly review"),
                    ),
                ).content as ReusableBlockContent.Template

        assertThat(database.title).isEqualTo("Team projects")
        assertThat(template.title).containsExactly(InlineContent.Text("Weekly review"))
    }

    @Test
    fun `maps native and custom emoji icons without losing display metadata`() {
        val native =
            mapper
                .map(
                    fixture.envelope(
                        type = "callout",
                        payload = """{"rich_text":[],"icon":{"type":"icon","icon":{"name":"library","color":"blue"}}}""",
                    ),
                ).content as TextBlockContent.Callout
        val customEmoji =
            mapper
                .map(
                    fixture.envelope(
                        type = "callout",
                        payload =
                            """
                            {"rich_text":[],"icon":{"type":"custom_emoji","custom_emoji":{"id":"emoji-id","name":"parrot",
                              "url":"https://example.com/parrot.png"}}}
                            """.trimIndent(),
                    ),
                ).content as TextBlockContent.Callout

        assertThat(native.icon).isEqualTo(BlockIcon.Native("library", ColorToken.BLUE))
        assertThat(customEmoji.icon).isEqualTo(
            BlockIcon.CustomEmoji(
                externalId = "emoji-id",
                name = "parrot",
                source = MediaSource.External(URI("https://example.com/parrot.png")),
            ),
        )
    }

    @Test
    fun `rejects malformed known blocks but keeps an unknown type as a child preserving fallback`() {
        assertThatThrownBy { mapper.map(fixture.envelope(type = "code", payload = "{\"rich_text\":[]}")) }
            .isInstanceOf(NotionBlockMappingException::class.java)

        val child = BlockNode(BlockId("child"), TextBlockContent.Paragraph(emptyList()))
        val fallback = mapper.map(fixture.envelope(type = "future_widget", payload = "{}"), listOf(child))

        assertThat(fallback.content).isEqualTo(UnsupportedBlockContent("future_widget"))
        assertThat(fallback.children).containsExactly(child)
    }

    @Test
    fun `requires unsupported payload to retain its open underlying block type`() {
        assertThatThrownBy { mapper.map(fixture.envelope(type = "unsupported", payload = "{}")) }
            .isInstanceOf(NotionBlockMappingException::class.java)
    }

    private fun richTextPayload(content: String): String =
        """
        {"rich_text":[{"type":"text","text":{"content":"$content","link":null},"annotations":{"color":"default"},
          "plain_text":"$content","href":null}]}
        """.trimIndent()

    private fun readResource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing resource $path" }
            .bufferedReader()
            .use { it.readText() }

    private fun JsonNode.required(name: String): JsonNode = get(name) ?: error("missing $name")
}
