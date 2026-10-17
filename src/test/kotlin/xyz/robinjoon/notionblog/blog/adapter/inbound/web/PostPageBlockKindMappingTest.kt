package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.assembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.dataTable
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ListView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.UnsupportedView
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
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
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.style.WidthToken
import java.net.URI

class PostPageBlockKindMappingTest {
    @Test
    fun `maps every normalized block subtype without dropping nested fallback children`() {
        val reference = SourceDocumentRef(SourceId("notion"), "reference")
        val rich = listOf(InlineContent.Text("text"))
        val page = page(textAndLayoutBlocks(rich) + mediaAndReferenceBlocks(rich, reference))

        val blocks = assembler.assemble(page).post.blocks
        val kinds = blocks.map { it.kind.name }
        val listItems = blocks.filterIsInstance<ListView>().flatMap { it.items }.map { it::class.simpleName }

        assertThat(kinds).contains(
            "PARAGRAPH",
            "HEADING",
            "QUOTE",
            "TOGGLE",
            "CALLOUT",
            "CODE",
            "EQUATION",
            "LIST",
            "DIVIDER",
            "COLUMN_LIST",
            "TAB_CONTAINER",
            "TABLE",
            "DATA_TABLE",
            "DATA_LIST",
            "DATA_GALLERY",
            "MEDIA",
            "BOOKMARK",
            "LINK_PREVIEW",
            "EMBED",
            "CHILD_POST",
            "DOCUMENT_LINK",
            "DATABASE_LINK",
            "BREADCRUMB",
            "TABLE_OF_CONTENTS",
            "SYNCHRONIZED",
            "TEMPLATE",
            "MEETING_NOTES",
            "UNSUPPORTED",
        )
        assertThat(listItems).contains("BulletedListItemView", "NumberedListItemView", "TodoListItemView")
        assertThat((blocks.last() as UnsupportedView).originalType).isEqualTo("future_block")
        assertThat((blocks.last() as UnsupportedView).children).hasSize(1)
    }

    private fun textAndLayoutBlocks(rich: List<InlineContent>): List<BlockNode> =
        listOf(
            node("paragraph", TextBlockContent.Paragraph(rich)),
            node("heading-one", TextBlockContent.Heading(HeadingLevel.ONE, rich)),
            node("heading-two", TextBlockContent.Heading(HeadingLevel.TWO, rich)),
            node("heading-three", TextBlockContent.Heading(HeadingLevel.THREE, rich)),
            node("heading-four", TextBlockContent.Heading(HeadingLevel.FOUR, rich)),
            node("quote", TextBlockContent.Quote(rich)),
            node("toggle", TextBlockContent.Toggle(rich)),
            node("callout", TextBlockContent.Callout(rich, BlockIcon.Emoji("💡"))),
            node("code", TextBlockContent.Code(rich, "kotlin", rich)),
            node("equation", TextBlockContent.Equation("x = y")),
            node("bullet", ListBlockContent.BulletedItem(rich)),
            node("numbered", ListBlockContent.NumberedItem(rich, 3, NumberedListFormat.UPPER_ROMAN)),
            node("todo", ListBlockContent.ToDoItem(rich, true)),
            node("divider", LayoutBlockContent.Divider),
            BlockNode(
                BlockId("columns"),
                LayoutBlockContent.ColumnList,
                children =
                    listOf(
                        BlockNode(
                            BlockId("column"),
                            LayoutBlockContent.Column(WidthToken(0.5)),
                            children = listOf(node("column-child", TextBlockContent.Paragraph(rich))),
                        ),
                    ),
            ),
            BlockNode(
                BlockId("tabs"),
                LayoutBlockContent.TabContainer,
                children =
                    listOf(
                        BlockNode(
                            BlockId("tab"),
                            LayoutBlockContent.TabItem(rich, null),
                            children = listOf(node("tab-child", TextBlockContent.Paragraph(rich))),
                        ),
                    ),
            ),
            BlockNode(
                BlockId("table"),
                LayoutBlockContent.Table(1, true, true),
                children = listOf(BlockNode(BlockId("row"), LayoutBlockContent.TableRow(listOf(rich)))),
            ),
            node("data-table", dataTable("Database view", listOf("Name"), listOf(DataRow(listOf(rich))))),
            node(
                "data-list",
                DataViewContent.ListView(DataSet("Database list", listOf(DataColumn("Name")), listOf(DataRow(listOf(rich))))),
            ),
            node(
                "data-gallery",
                DataViewContent.Gallery(DataSet("Database gallery", listOf(DataColumn("Name")), listOf(DataRow(listOf(rich))))),
            ),
        )

    private fun mediaAndReferenceBlocks(
        rich: List<InlineContent>,
        reference: SourceDocumentRef,
    ): List<BlockNode> =
        listOf(
            node("image", MediaBlockContent.Media(MediaType.IMAGE, MediaSource.External(URI("https://example.com/image")), "image")),
            node("video", MediaBlockContent.Media(MediaType.VIDEO, MediaSource.External(URI("https://example.com/video")), "video")),
            node("audio", MediaBlockContent.Media(MediaType.AUDIO, MediaSource.External(URI("https://example.com/audio")), "audio")),
            node("file", MediaBlockContent.Media(MediaType.FILE, MediaSource.External(URI("https://example.com/file")), "file")),
            node("pdf", MediaBlockContent.Media(MediaType.PDF, MediaSource.External(URI("https://example.com/pdf")), "pdf")),
            node("bookmark", MediaBlockContent.Bookmark(URI("https://example.com/bookmark"), rich)),
            node("preview", MediaBlockContent.LinkPreview(URI("https://example.com/preview"))),
            node("embed", MediaBlockContent.Embed(URI("https://www.youtube.com/embed/id"), rich)),
            node("child", ReferenceBlockContent.ChildPost("Child", reference)),
            node("document", ReferenceBlockContent.DocumentLink(reference, URI("https://example.com/document"))),
            node("database", ReferenceBlockContent.DatabaseLink(reference, URI("https://example.com/database"), "Database")),
            node(
                "breadcrumb",
                ReferenceBlockContent.Breadcrumb(listOf(LinkTarget.SourceDocument(reference, URI("https://example.com/breadcrumb")))),
            ),
            node("toc", ReferenceBlockContent.TableOfContents),
            node("synced", ReusableBlockContent.Synchronized(SynchronizedBlockOrigin(reference, "origin"))),
            node("template", ReusableBlockContent.Template(rich)),
            node("meeting", SpecialBlockContent.MeetingNotes("Meeting", MeetingNotesStatus.COMPLETED, rich, null)),
            BlockNode(
                BlockId("unsupported"),
                UnsupportedBlockContent("future_block"),
                children = listOf(node("fallback-child", TextBlockContent.Paragraph(rich))),
            ),
        )
}
