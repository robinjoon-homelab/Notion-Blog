package xyz.robinjoon.notionblog.adapter.output.persistence.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.domain.post.block.content.MeetingNotesStatus
import xyz.robinjoon.notionblog.domain.post.block.content.NumberedListFormat
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.inline.MentionKind
import xyz.robinjoon.notionblog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.post.block.style.Alignment
import xyz.robinjoon.notionblog.domain.post.block.style.BlockStyle
import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken
import xyz.robinjoon.notionblog.domain.post.block.style.StyleVariant
import xyz.robinjoon.notionblog.domain.post.block.style.WidthToken
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.time.Instant

class JsonBlockTreeSnapshotCompatibilityTest {
    private val codec = JsonBlockTreeSnapshotCodec()
    private val mapper = JsonMapper.builder().build()

    @ParameterizedTest
    @ValueSource(strings = ["rich-content-explicit-nulls.json", "rich-content-omitted-fields.json"])
    fun `decodes frozen version one rich content without losing styles links media or nested blocks`(fixtureName: String) {
        val paragraph = BlockNode(
            BlockId("paragraph"),
            TextBlockContent.Paragraph(
                listOf(
                    InlineContent.Text(
                        "Linked text",
                        TextAnnotations(
                            bold = true,
                            italic = true,
                            strikethrough = true,
                            underline = true,
                            code = true,
                            foreground = ColorToken.ORANGE,
                            background = ColorToken.PURPLE,
                        ),
                        LinkTarget.SourceDocument(
                            SourceDocumentRef(SourceId("notion-main"), "guide"),
                            URI("https://example.com/guide"),
                        ),
                    ),
                    InlineContent.Equation("x^2", TextAnnotations(italic = true)),
                    InlineContent.Mention(
                        "Tomorrow",
                        MentionKind.DATE,
                        target = LinkTarget.ExternalUrl(URI("https://example.com/calendar")),
                    ),
                    InlineContent.Mention(
                        "Draft",
                        MentionKind.DOCUMENT,
                        target = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion-main"), "draft"), null),
                    ),
                ),
            ),
        )
        val media = BlockNode(
            BlockId("pdf"),
            MediaBlockContent.Media(
                MediaType.PDF,
                MediaSource.SourceHosted(URI("https://example.com/guide.pdf"), Instant.parse("2026-09-01T00:00:00Z")),
                "guide.pdf",
                listOf(InlineContent.Text("Download")),
            ),
        )
        val image = BlockNode(
            BlockId("image"),
            MediaBlockContent.Media(MediaType.IMAGE, MediaSource.SourceHosted(URI("https://example.com/image.png"), null), null),
        )
        val expected = BlockTree(
            listOf(
                BlockNode(
                    BlockId("heading"),
                    TextBlockContent.Heading(HeadingLevel.FOUR, listOf(InlineContent.Text("Guide")), isToggleable = true),
                    BlockStyle(ColorToken.RED, ColorToken.BLUE, Alignment.CENTER, WidthToken(0.75), StyleVariant("featured")),
                    children = listOf(paragraph),
                ),
                BlockNode(
                    BlockId("columns"),
                    LayoutBlockContent.ColumnList,
                    children = listOf(
                        BlockNode(BlockId("column"), LayoutBlockContent.Column(WidthToken(0.5)), children = listOf(media)),
                        BlockNode(BlockId("auto-column"), LayoutBlockContent.Column(null), children = listOf(image)),
                    ),
                ),
                BlockNode(
                    BlockId("numbered"),
                    ListBlockContent.NumberedItem(emptyList(), 3, NumberedListFormat.UPPER_ROMAN, startsNewList = true),
                ),
                BlockNode(BlockId("meeting"), SpecialBlockContent.MeetingNotes("Planning", MeetingNotesStatus.IN_PROGRESS, emptyList(), null)),
            ),
        )

        assertThat(codec.decode(fixture(fixtureName))).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["data-views-explicit-nulls.json", "data-views-omitted-fields.json"])
    fun `decodes frozen version one data views with distinct layouts options and row metadata`(fixtureName: String) {
        val table = DataViewContent.Table(
            DataSet(
                "Tasks",
                listOf(DataColumn("Status", 0, true), DataColumn("Name", 260, false)),
                listOf(DataRow(listOf(emptyList(), listOf(InlineContent.Text("Publish"))))),
                titleColumnIndex = 1,
            ),
            DataTableOptions(wrapCells = false, frozenColumns = 1, showVerticalLines = false),
        )
        val list = DataViewContent.ListView(DataSet("No tasks", listOf(DataColumn("Name")), emptyList()))
        val gallery = DataViewContent.Gallery(
            DataSet(
                "Gallery",
                listOf(DataColumn("Name")),
                listOf(
                    DataRow(
                        cells = listOf(listOf(InlineContent.Text("Preview"))),
                        link = LinkTarget.SourceDocument(
                            SourceDocumentRef(SourceId("notion-main"), "preview"),
                            URI("https://example.com/preview"),
                        ),
                        icon = BlockIcon.CustomEmoji("parrot-id", "parrot", MediaSource.External(URI("https://example.com/parrot.png"))),
                        cover = MediaSource.SourceHosted(URI("https://example.com/cover.png"), Instant.parse("2026-09-01T00:00:00Z")),
                    ),
                ),
                titleColumnIndex = 0,
            ),
            DataGalleryOptions(DataCardSize.LARGE, DataCoverAspect.CONTAIN, DataCardLayout.COMPACT),
        )
        val expected = BlockTree(
            listOf(
                BlockNode(
                    BlockId("views"),
                    LayoutBlockContent.TabContainer,
                    children = listOf(
                        BlockNode(
                            BlockId("table-tab"),
                            LayoutBlockContent.TabItem(emptyList(), BlockIcon.Emoji("📋")),
                            children = listOf(BlockNode(BlockId("table-view"), table)),
                        ),
                        BlockNode(
                            BlockId("list-tab"),
                            LayoutBlockContent.TabItem(emptyList(), BlockIcon.Native("list", ColorToken.BLUE)),
                            children = listOf(BlockNode(BlockId("list-view"), list)),
                        ),
                        BlockNode(
                            BlockId("gallery-tab"),
                            LayoutBlockContent.TabItem(emptyList(), null),
                            children = listOf(BlockNode(BlockId("gallery-view"), gallery)),
                        ),
                    ),
                ),
            ),
        )

        assertThat(codec.decode(fixture(fixtureName))).isEqualTo(expected)
    }

    @Test
    fun `explicit null fixtures keep every nullable field null while omitted field fixtures drop them`() {
        val documents = listOf("rich-content", "data-views")
        val explicitNullFields = documents.flatMap { nullFieldNames(mapper.readTree(fixture("$it-explicit-nulls.json"))) }

        // One field per optional decoder path: object, text, enum, int, double, boolean, URI and instant values.
        assertThat(explicitNullFields).contains(
            "link", "icon", "cover", "notesReference", "variant", "fileName", "foreground", "background", "alignment",
            "titleColumnIndex", "widthPixels", "width", "wrap", "originalUrl", "expiresAt",
        )
        documents.forEach { document ->
            assertThat(nullFieldNames(mapper.readTree(fixture("$document-omitted-fields.json")))).isEmpty()
        }
    }

    private fun nullFieldNames(node: JsonNode): List<String> = if (node is ObjectNode) {
        node.propertyNames().flatMap { name -> node.get(name).let { value -> if (value.isNull) listOf(name) else nullFieldNames(value) } }
    } else {
        node.values().flatMap(::nullFieldNames)
    }

    // Frozen inputs: *-explicit-nulls.json is schema-v1 encoder output captured once on 2026-10-04, and *-omitted-fields.json is
    // the same document without its null fields. Never regenerate them from the current encoder so paired mapper changes cannot
    // hide incompatibility with stored snapshots.
    private fun fixture(name: String): String = checkNotNull(javaClass.getResourceAsStream("/snapshot/schema-v1/$name"))
        .bufferedReader()
        .use { it.readText() }
}
