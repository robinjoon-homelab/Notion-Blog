package xyz.robinjoon.notionblog.adapter.output.persistence.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
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
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SynchronizedBlockOrigin
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
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
        val tree = BlockTree(
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
        val data = DataSet(
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
        val views = listOf(
            DataViewContent.Table(data, DataTableOptions(wrapCells = false, frozenColumns = 2, showVerticalLines = false)),
            DataViewContent.ListView(data),
            DataViewContent.Gallery(data, DataGalleryOptions(DataCardSize.LARGE, DataCoverAspect.CONTAIN, DataCardLayout.COMPACT)),
        )
        val tree = BlockTree(
            listOf(
                node(
                    "database",
                    ReferenceBlockContent.DatabaseLink(sourceReference, URI("https://example.com/database"), "Tasks"),
                    children = listOf(
                        node(
                            "tabs",
                            LayoutBlockContent.TabContainer,
                            children = views.mapIndexed { index, view ->
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
        assertThat(encoded).contains("\"schemaVersion\":1", "\"kind\":\"data_view\"", "\"layout\":\"table\"", "\"layout\":\"list\"", "\"layout\":\"gallery\"")
        assertThat(encoded).doesNotContain("data_table", "xyz.", "java.", "kotlin.", "@class", "\"type\"")
    }

    @Test
    fun `round trips empty data views without dropping columns or default options`() {
        val data = DataSet("No tasks", listOf(DataColumn("Name")), emptyList())
        val tree = BlockTree(
            listOf(
                node("empty-table", DataViewContent.Table(data)),
                node("empty-list", DataViewContent.ListView(data)),
                node("empty-gallery", DataViewContent.Gallery(data)),
            ),
        )

        assertThat(codec.decode(codec.encode(tree))).isEqualTo(tree)
    }

    @Test
    fun `decodes an existing data table snapshot into the canonical table layout`() {
        val decoded = codec.decode(
            checkNotNull(javaClass.getResourceAsStream("/snapshot/schema-v1/legacy-data-table.json"))
                .bufferedReader()
                .use { it.readText() },
        )

        val expected = DataViewContent.Table(
            DataSet(
                "Legacy tasks",
                listOf(DataColumn("Name"), DataColumn("Status")),
                listOf(
                    DataRow(
                        listOf(
                            listOf(InlineContent.Text("Published row", TextAnnotations(bold = true), LinkTarget.ExternalUrl(URI("https://example.com/row")))),
                            emptyList(),
                        ),
                    ),
                ),
            ),
        )
        assertThat(decoded.roots.single().content).isEqualTo(expected)
        assertThat(codec.encode(decoded)).contains("\"kind\":\"data_view\"", "\"layout\":\"table\"").doesNotContain("data_table")
    }

    @Test
    fun `rejects unsupported data view layouts and malformed layout options`() {
        listOf(
            "\"layout\":\"board\"" to "unsupported data view layout",
            "\"layout\":\"table\",\"options\":{\"wrapCells\":true,\"frozenColumns\":2,\"showVerticalLines\":true}" to "frozen column count",
            "\"layout\":\"table\",\"options\":{\"wrapCells\":true,\"frozenColumns\":0.5,\"showVerticalLines\":true}" to "integer",
            "\"layout\":\"gallery\",\"options\":{\"size\":\"huge\",\"aspect\":\"cover\",\"layout\":\"list\"}" to "data card size",
        ).forEach { (layout, message) ->
            assertThatIllegalArgumentException().isThrownBy {
                codec.decode(
                    """{"schemaVersion":1,"kind":"block_tree_snapshot","blocks":[{"id":"bad-view","kind":"data_view","style":{},"content":{$layout,"data":{"title":"Tasks","columns":[{"name":"Name"}],"rows":[]}},"children":[]}]}""",
                )
            }.withMessageContaining(message)
        }
    }

    @Test
    fun `rejects malformed shared dataset fields instead of truncating or guessing`() {
        listOf(
            "\"columns\":[{\"name\":\"Name\",\"widthPixels\":12.5}],\"rows\":[]" to "integer",
            "\"columns\":[{\"name\":\"Name\",\"wrap\":\"yes\"}],\"rows\":[]" to "boolean",
            "\"columns\":[{\"name\":\"Name\"}],\"rows\":[],\"titleColumnIndex\":1" to "title column index",
            "\"columns\":[{\"name\":\"Name\"}],\"rows\":[{\"cells\":[]}]" to "column count",
        ).forEach { (fields, message) ->
            assertThatIllegalArgumentException().isThrownBy {
                codec.decode(
                    """{"schemaVersion":1,"kind":"block_tree_snapshot","blocks":[{"id":"bad-data","kind":"data_view","style":{},"content":{"layout":"list","data":{"title":"Tasks",$fields}},"children":[]}]}""",
                )
            }.withMessageContaining(message)
        }
    }

    @Test
    fun `rejects data table snapshots with mismatched row widths`() {
        assertThatIllegalArgumentException().isThrownBy {
            codec.decode(
                """
                {
                  "schemaVersion": 1,
                  "kind": "block_tree_snapshot",
                  "blocks": [{
                    "id": "broken-data-table",
                    "kind": "data_table",
                    "style": {},
                    "content": {"title": "Tasks", "columns": ["Name", "Status"], "rows": [{"cells": [[]]}]},
                    "children": []
                  }]
                }
                """.trimIndent(),
            )
        }.withMessage("data set rows must contain exactly the column count")
    }

    @Test
    fun `falls back to unsupported content for an unknown logical block kind`() {
        val decoded = codec.decode(
            """
            {
              "schemaVersion": 1,
              "kind": "block_tree_snapshot",
              "blocks": [
                {
                  "id": "future-block",
                  "kind": "future_widget",
                  "style": {},
                  "content": "unrecognized future payload",
                  "children": [{
                    "id": "child",
                    "kind": "paragraph",
                    "style": {},
                    "content": {"richText": []},
                    "children": []
                  }]
                }
              ]
            }
            """.trimIndent(),
        )

        assertThat(decoded.roots.single().content).isEqualTo(UnsupportedBlockContent("future_widget"))
        assertThat(decoded.roots.single().children.single().content).isEqualTo(TextBlockContent.Paragraph(emptyList()))
    }

    @Test
    fun `rejects unsupported snapshot schema versions`() {
        assertThatIllegalArgumentException().isThrownBy {
            codec.decode("""{"schemaVersion":2,"kind":"block_tree_snapshot","blocks":[]}""")
        }.withMessage("unsupported block tree snapshot schema version: 2")
    }

    @Test
    fun `rejects malformed known block kinds instead of disguising them as unsupported`() {
        assertThatIllegalArgumentException().isThrownBy {
            codec.decode(
                """
                {
                  "schemaVersion": 1,
                  "kind": "block_tree_snapshot",
                  "blocks": [{"id":"broken","kind":"paragraph","style":{},"children":[]}]
                }
                """.trimIndent(),
            )
        }.withMessageContaining("paragraph")
    }

    @Test
    fun `decodes older schema version one snapshots with defaults for newly preserved block fields`() {
        val decoded = codec.decode(
            """
            {
              "schemaVersion": 1,
              "kind": "block_tree_snapshot",
              "blocks": [
                {
                  "id":"numbered",
                  "kind":"numbered_list_item",
                  "style":{},
                  "content":{"richText":[],"startNumber":1,"displayFormat":"decimal"},
                  "children":[]
                },
                {
                  "id":"database",
                  "kind":"database_link",
                  "style":{},
                  "content":{"reference":{"sourceId":"notion-main","externalId":"database-id"},"originalUrl":null},
                  "children":[]
                },
                {
                  "id":"template",
                  "kind":"template",
                  "style":{},
                  "content":{},
                  "children":[]
                }
              ]
            }
            """.trimIndent(),
        )

        assertThat(decoded.roots[0].content).isEqualTo(ListBlockContent.NumberedItem(emptyList()))
        assertThat(decoded.roots[1].content).isEqualTo(
            ReferenceBlockContent.DatabaseLink(SourceDocumentRef(SourceId("notion-main"), "database-id"), null),
        )
        assertThat(decoded.roots[2].content).isEqualTo(ReusableBlockContent.Template())
    }

    private fun completeBlockTree(): BlockTree = BlockTree(
        listOf(
            node("paragraph", TextBlockContent.Paragraph(listOf(linkedText()))),
            node("heading", TextBlockContent.Heading(HeadingLevel.FOUR, listOf(InlineContent.Text("Heading")), true)),
            node("quote", TextBlockContent.Quote(listOf(InlineContent.Equation("E=mc^2")))),
            node("toggle", TextBlockContent.Toggle(listOf(InlineContent.Text("Toggle")))),
            node("callout", TextBlockContent.Callout(listOf(InlineContent.Text("Callout")), BlockIcon.Media(MediaSource.External(URI("https://example.com/icon.png"))))),
            node("native-icon", TextBlockContent.Callout(listOf(InlineContent.Text("Native")), BlockIcon.Native("library", ColorToken.BLUE))),
            node("custom-emoji", TextBlockContent.Callout(listOf(InlineContent.Text("Custom")), BlockIcon.CustomEmoji("emoji-id", "parrot", MediaSource.External(URI("https://example.com/parrot.png"))))),
            node("code", TextBlockContent.Code(listOf(InlineContent.Text("println()")), "kotlin", listOf(InlineContent.Text("Caption")))),
            node("equation", TextBlockContent.Equation("a+b")),
            node("bullet", ListBlockContent.BulletedItem(listOf(InlineContent.Text("Bullet")))),
            node("numbered", ListBlockContent.NumberedItem(listOf(InlineContent.Text("Numbered")), 3, NumberedListFormat.UPPER_ROMAN, startsNewList = true)),
            node("todo", ListBlockContent.ToDoItem(listOf(InlineContent.Text("To do")), true)),
            node("divider", LayoutBlockContent.Divider),
            node("columns", LayoutBlockContent.ColumnList, children = listOf(node("column", LayoutBlockContent.Column(WidthToken(0.5))))),
            node("tabs", LayoutBlockContent.TabContainer, children = listOf(node("tab", LayoutBlockContent.TabItem(listOf(InlineContent.Text("Tab")), BlockIcon.Emoji("📌"))))),
            node("table", LayoutBlockContent.Table(1, hasColumnHeader = true, hasRowHeader = true), children = listOf(node("table-row", LayoutBlockContent.TableRow(listOf(listOf(InlineContent.Text("Cell"))))))),
            node("data-table", DataViewContent.Table(DataSet("Data", listOf(DataColumn("Name")), listOf(DataRow(listOf(listOf(linkedText()))))))),
            node("data-list", DataViewContent.ListView(DataSet("Data", listOf(DataColumn("Name")), emptyList()))),
            node("data-gallery", DataViewContent.Gallery(DataSet("Data", listOf(DataColumn("Name")), emptyList()))),
            node("child-post", ReferenceBlockContent.ChildPost("Child", sourceReference)),
            node("document-link", ReferenceBlockContent.DocumentLink(sourceReference, URI("https://example.com/document"))),
            node("database-link", ReferenceBlockContent.DatabaseLink(sourceReference, null, "Database")),
            node("breadcrumb", ReferenceBlockContent.Breadcrumb(listOf(LinkTarget.ExternalUrl(URI("https://example.com")), LinkTarget.SourceDocument(sourceReference, null)))),
            node("table-of-contents", ReferenceBlockContent.TableOfContents),
            node("media", MediaBlockContent.Media(MediaType.PDF, MediaSource.SourceHosted(URI("https://example.com/file.pdf"), Instant.parse("2026-08-25T00:00:00Z")), "file.pdf", listOf(InlineContent.Text("PDF")))),
            node("bookmark", MediaBlockContent.Bookmark(URI("https://example.com/bookmark"), listOf(InlineContent.Text("Bookmark")))),
            node("link-preview", MediaBlockContent.LinkPreview(URI("https://example.com/preview"))),
            node("embed", MediaBlockContent.Embed(URI("https://example.com/embed"), listOf(InlineContent.Text("Embed")))),
            node("synchronized", ReusableBlockContent.Synchronized(SynchronizedBlockOrigin(sourceReference, "origin-block"))),
            node("template", ReusableBlockContent.Template(listOf(InlineContent.Text("Template")))),
            node("meeting-notes", SpecialBlockContent.MeetingNotes("Meeting", MeetingNotesStatus.IN_PROGRESS, listOf(InlineContent.Mention("Jane", MentionKind.USER, target = LinkTarget.ExternalUrl(URI("https://example.com/jane")))), LinkTarget.SourceDocument(sourceReference, URI("https://example.com/notes")))),
            node("unsupported", UnsupportedBlockContent("future_type")),
        ),
    )

    private fun node(
        id: String,
        content: xyz.robinjoon.notionblog.domain.post.block.content.BlockContent,
        children: List<BlockNode> = emptyList(),
    ): BlockNode = BlockNode(
        id = BlockId(id),
        content = content,
        style = BlockStyle(ColorToken.RED, ColorToken.BLUE, Alignment.CENTER, WidthToken(0.75), StyleVariant("featured")),
        children = children,
    )

    private fun linkedText(): InlineContent.Text = InlineContent.Text(
        "Linked text",
        TextAnnotations(bold = true, italic = true, strikethrough = true, underline = true, code = true, foreground = ColorToken.ORANGE, background = ColorToken.PURPLE),
        LinkTarget.SourceDocument(sourceReference, URI("https://example.com/original")),
    )

    private companion object {
        val sourceReference = SourceDocumentRef(SourceId("notion-main"), "external-document")
    }
}
