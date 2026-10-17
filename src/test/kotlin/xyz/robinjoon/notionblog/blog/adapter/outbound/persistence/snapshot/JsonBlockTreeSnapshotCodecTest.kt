package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot

import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.HeadingLevel
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MeetingNotesStatus
import xyz.robinjoon.notionblog.blog.domain.post.block.content.NumberedListFormat
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.SynchronizedBlockOrigin
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.MentionKind
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.style.Alignment
import xyz.robinjoon.notionblog.blog.domain.post.block.style.BlockStyle
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken
import xyz.robinjoon.notionblog.blog.domain.post.block.style.StyleVariant
import xyz.robinjoon.notionblog.blog.domain.post.block.style.WidthToken
import java.net.URI
import java.time.Instant

class JsonBlockTreeSnapshotCodecTest {
    private val codec = JsonBlockTreeSnapshotCodec()

    @Test
    fun `round trips every block content subtype using stable logical kinds`() {
        val tree = completeBlockTree()

        val encoded = codec.encode(tree)

        assertThat(codec.decode(encoded)).isEqualTo(tree)
        val document = JsonMapper.builder().build().readTree(encoded)
        assertThat(document.get("schemaVersion").asInt()).isEqualTo(1)
        assertThat(document.get("kind").asString()).isEqualTo("block_tree_snapshot")
        assertThat(document.get("blocks").size()).isGreaterThan(0)
        assertThat(encoded).doesNotContain("xyz.", "java.", "kotlin.", "@class", "\"type\"")
    }

    @Test
    fun `preserves nested children and inline source document links`() {
        val tree =
            BlockTree(
                listOf(
                    node(
                        "parent",
                        TextBlockContent.Paragraph(listOf(linkedText())),
                        children = listOf(node("child", TextBlockContent.Quote(listOf(InlineContent.Equation("x^2"))))),
                    ),
                ),
            )

        assertThat(codec.decode(codec.encode(tree))).isEqualTo(tree)
    }

    @Test
    fun `round trips all data view layouts inside database tabs with typed options and row metadata`() {
        val data =
            DataSet(
                "Open tasks",
                listOf(DataColumn("Name", 260, false), DataColumn("Details", 0, true)),
                listOf(
                    DataRow(
                        listOf(
                            listOf(linkedText()),
                            listOf(
                                InlineContent.Equation("x^2", TextAnnotations(italic = true)),
                                InlineContent.Mention("Tomorrow", MentionKind.DATE),
                                InlineContent.Text("More", link = LinkTarget.ExternalUrl(URI("https://example.com/more"))),
                            ),
                        ),
                        link = LinkTarget.ExternalUrl(URI("https://example.com/task")),
                        icon = BlockIcon.Emoji("📌"),
                        cover = MediaSource.SourceHosted(URI("https://example.com/cover.png"), Instant.parse("2026-09-01T00:00:00Z")),
                    ),
                    DataRow(listOf(emptyList(), emptyList())),
                ),
                titleColumnIndex = 0,
            )
        val views =
            listOf(
                DataViewContent.Table(data, DataTableOptions(wrapCells = false, frozenColumns = 2, showVerticalLines = false)),
                DataViewContent.ListView(data),
                DataViewContent.Gallery(data, DataGalleryOptions(DataCardSize.LARGE, DataCoverAspect.CONTAIN, DataCardLayout.COMPACT)),
            )
        val tree =
            BlockTree(
                listOf(
                    node(
                        "database",
                        ReferenceBlockContent.DatabaseLink(sourceReference, URI("https://example.com/database"), "Tasks"),
                        children =
                            listOf(
                                node(
                                    "tabs",
                                    LayoutBlockContent.TabContainer,
                                    children =
                                        views.mapIndexed { index, view ->
                                            node(
                                                "tab-$index",
                                                LayoutBlockContent.TabItem(listOf(InlineContent.Text("Open tasks")), null),
                                                children = listOf(node("data-view-$index", view)),
                                            )
                                        },
                                ),
                            ),
                    ),
                ),
            )

        val encoded = codec.encode(tree)

        assertThat(codec.decode(encoded)).isEqualTo(tree)
        assertThat(
            encoded,
        ).contains("\"schemaVersion\":1", "\"kind\":\"data_view\"", "\"layout\":\"table\"", "\"layout\":\"list\"", "\"layout\":\"gallery\"")
        assertThat(encoded).doesNotContain("data_table", "xyz.", "java.", "kotlin.", "@class", "\"type\"")
    }

    @Test
    fun `round trips empty data views without dropping columns or default options`() {
        val data = DataSet("No tasks", listOf(DataColumn("Name")), emptyList())
        val tree =
            BlockTree(
                listOf(
                    node("empty-table", DataViewContent.Table(data)),
                    node("empty-list", DataViewContent.ListView(data)),
                    node("empty-gallery", DataViewContent.Gallery(data)),
                ),
            )

        assertThat(codec.decode(codec.encode(tree))).isEqualTo(tree)
    }

    private fun completeBlockTree(): BlockTree =
        BlockTree(textAndListBlocks() + layoutAndDataBlocks() + referenceBlocks() + mediaAndSpecialBlocks())

    private fun textAndListBlocks(): List<BlockNode> =
        listOf(
            node("paragraph", TextBlockContent.Paragraph(listOf(linkedText()))),
            node("heading", TextBlockContent.Heading(HeadingLevel.FOUR, listOf(InlineContent.Text("Heading")), true)),
            node("quote", TextBlockContent.Quote(listOf(InlineContent.Equation("E=mc^2")))),
            node("toggle", TextBlockContent.Toggle(listOf(InlineContent.Text("Toggle")))),
            node(
                "callout",
                TextBlockContent.Callout(
                    listOf(InlineContent.Text("Callout")),
                    BlockIcon.Media(MediaSource.External(URI("https://example.com/icon.png"))),
                ),
            ),
            node(
                "native-icon",
                TextBlockContent.Callout(listOf(InlineContent.Text("Native")), BlockIcon.Native("library", ColorToken.BLUE)),
            ),
            node(
                "custom-emoji",
                TextBlockContent.Callout(
                    listOf(InlineContent.Text("Custom")),
                    BlockIcon.CustomEmoji("emoji-id", "parrot", MediaSource.External(URI("https://example.com/parrot.png"))),
                ),
            ),
            node(
                "code",
                TextBlockContent.Code(listOf(InlineContent.Text("println()")), "kotlin", listOf(InlineContent.Text("Caption"))),
            ),
            node("equation", TextBlockContent.Equation("a+b")),
            node("bullet", ListBlockContent.BulletedItem(listOf(InlineContent.Text("Bullet")))),
            node(
                "numbered",
                ListBlockContent.NumberedItem(
                    listOf(InlineContent.Text("Numbered")),
                    3,
                    NumberedListFormat.UPPER_ROMAN,
                    startsNewList = true,
                ),
            ),
            node("todo", ListBlockContent.ToDoItem(listOf(InlineContent.Text("To do")), true)),
        )

    private fun layoutAndDataBlocks(): List<BlockNode> =
        listOf(
            node("divider", LayoutBlockContent.Divider),
            node(
                "columns",
                LayoutBlockContent.ColumnList,
                children = listOf(node("column", LayoutBlockContent.Column(WidthToken(0.5)))),
            ),
            node(
                "tabs",
                LayoutBlockContent.TabContainer,
                children = listOf(node("tab", LayoutBlockContent.TabItem(listOf(InlineContent.Text("Tab")), BlockIcon.Emoji("📌")))),
            ),
            node(
                "table",
                LayoutBlockContent.Table(1, hasColumnHeader = true, hasRowHeader = true),
                children = listOf(node("table-row", LayoutBlockContent.TableRow(listOf(listOf(InlineContent.Text("Cell")))))),
            ),
            node(
                "data-table",
                DataViewContent.Table(DataSet("Data", listOf(DataColumn("Name")), listOf(DataRow(listOf(listOf(linkedText())))))),
            ),
            node("data-list", DataViewContent.ListView(DataSet("Data", listOf(DataColumn("Name")), emptyList()))),
            node("data-gallery", DataViewContent.Gallery(DataSet("Data", listOf(DataColumn("Name")), emptyList()))),
        )

    private fun referenceBlocks(): List<BlockNode> =
        listOf(
            node("child-post", ReferenceBlockContent.ChildPost("Child", sourceReference)),
            node("document-link", ReferenceBlockContent.DocumentLink(sourceReference, URI("https://example.com/document"))),
            node("database-link", ReferenceBlockContent.DatabaseLink(sourceReference, null, "Database")),
            node(
                "breadcrumb",
                ReferenceBlockContent.Breadcrumb(
                    listOf(LinkTarget.ExternalUrl(URI("https://example.com")), LinkTarget.SourceDocument(sourceReference, null)),
                ),
            ),
            node("table-of-contents", ReferenceBlockContent.TableOfContents),
        )

    private fun mediaAndSpecialBlocks(): List<BlockNode> =
        listOf(
            node(
                "media",
                MediaBlockContent.Media(
                    MediaType.PDF,
                    MediaSource.SourceHosted(URI("https://example.com/file.pdf"), Instant.parse("2026-08-25T00:00:00Z")),
                    "file.pdf",
                    listOf(InlineContent.Text("PDF")),
                ),
            ),
            node("bookmark", MediaBlockContent.Bookmark(URI("https://example.com/bookmark"), listOf(InlineContent.Text("Bookmark")))),
            node("link-preview", MediaBlockContent.LinkPreview(URI("https://example.com/preview"))),
            node("embed", MediaBlockContent.Embed(URI("https://example.com/embed"), listOf(InlineContent.Text("Embed")))),
            node("synchronized", ReusableBlockContent.Synchronized(SynchronizedBlockOrigin(sourceReference, "origin-block"))),
            node("template", ReusableBlockContent.Template(listOf(InlineContent.Text("Template")))),
            node(
                "meeting-notes",
                SpecialBlockContent.MeetingNotes(
                    "Meeting",
                    MeetingNotesStatus.IN_PROGRESS,
                    listOf(
                        InlineContent.Mention(
                            "Jane",
                            MentionKind.USER,
                            target = LinkTarget.ExternalUrl(URI("https://example.com/jane")),
                        ),
                    ),
                    LinkTarget.SourceDocument(sourceReference, URI("https://example.com/notes")),
                ),
            ),
            node("unsupported", UnsupportedBlockContent("future_type")),
        )

    private fun node(
        id: String,
        content: xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockContent,
        children: List<BlockNode> = emptyList(),
    ): BlockNode =
        BlockNode(
            id = BlockId(id),
            content = content,
            style = BlockStyle(ColorToken.RED, ColorToken.BLUE, Alignment.CENTER, WidthToken(0.75), StyleVariant("featured")),
            children = children,
        )

    private fun linkedText(): InlineContent.Text =
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
            LinkTarget.SourceDocument(sourceReference, URI("https://example.com/original")),
        )

    private companion object {
        val sourceReference = SourceDocumentRef(SourceId("notion-main"), "external-document")
    }
}
