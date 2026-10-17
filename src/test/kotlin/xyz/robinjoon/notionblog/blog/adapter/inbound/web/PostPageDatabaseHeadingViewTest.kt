package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.dataTable
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.render
import xyz.robinjoon.notionblog.blog.application.model.LinkResolution
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import java.net.URI

class PostPageDatabaseHeadingViewTest {
    @Test
    fun `keeps database titles and view tabs around tables without unavailable announcements`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val tabs =
            listOf("Active", "Archive").mapIndexed { index, title ->
                BlockNode(
                    BlockId("database-view-$index"),
                    LayoutBlockContent.TabItem(listOf(InlineContent.Text(title)), null),
                    children = listOf(node("database-table-$index", dataTable(title, listOf("Task"), emptyList()))),
                )
            }
        val database =
            BlockNode(
                BlockId("database"),
                ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Team tasks"),
                children = listOf(BlockNode(BlockId("database-views"), LayoutBlockContent.TabContainer, children = tabs)),
            )

        val html =
            render(
                page(
                    listOf(database),
                    mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl)),
                ),
            )
        val markup = html.substringAfter("id=\"database\"").substringBefore("</aside>")

        assertThat(html).contains("notion-database")
        assertThat(
            markup,
        ).contains("Team tasks", "role=\"tablist\"", "aria-selected=\"true\"", "aria-selected=\"false\"", "Active", "Archive")
        assertThat(Regex("class=\"notion-table notion-data-table\"").findAll(markup).count()).isEqualTo(2)
        assertThat(markup).doesNotContain(" unavailable")
        assertThat(markup.substringBefore("</h2>")).contains("<span>Team tasks</span>").doesNotContain("<a", "href=")
    }

    @Test
    fun `renders a single database view directly with its repeated name available only to assistive technology`() {
        val viewName = "Untitled <script>unsafe</script>"
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val tab =
            BlockNode(
                BlockId("database-view"),
                LayoutBlockContent.TabItem(listOf(InlineContent.Text(viewName)), null),
                children =
                    listOf(
                        node(
                            "database-gallery",
                            DataViewContent.Gallery(
                                DataSet(
                                    viewName,
                                    listOf(DataColumn("Name")),
                                    listOf(DataRow(listOf(listOf(InlineContent.Text("Published note"))))),
                                    titleColumnIndex = 0,
                                ),
                            ),
                        ),
                    ),
            )
        val database =
            BlockNode(
                BlockId("database"),
                ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Blog posts"),
                children = listOf(BlockNode(BlockId("database-views"), LayoutBlockContent.TabContainer, children = listOf(tab))),
            )

        val html =
            render(
                page(
                    listOf(database),
                    mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl)),
                ),
            )
        val markup = html.substringAfter("id=\"database\"").substringBefore("</aside>")

        assertThat(markup).contains(
            "<h2 class=\"notion-database-title\"><span>Blog posts</span></h2>",
            "notion-data-gallery",
            "Published note",
        )
        assertThat(markup).doesNotContain("role=\"tablist\"", "role=\"tab\"", "role=\"tabpanel\"", "class=\"notion-tabs\"")
        assertThat(markup).contains(
            "<span class=\"notion-data-view-title notion-sr-only\"",
            "Untitled &lt;script&gt;unsafe&lt;/script&gt;",
        )
        assertThat(markup).doesNotContain("<h3 class=\"notion-data-view-title", "<script>unsafe</script>")
        assertThat(markup.substringBefore("</h2>")).doesNotContain("<a", "href=")
    }

    @Test
    fun `keeps the safe external database heading link when no inline body is available`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val database = node("database-fallback", ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Original database"))
        val html =
            render(
                page(
                    listOf(database),
                    mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl)),
                ),
            )
        val heading = html.substringAfter("class=\"notion-database-title\"").substringBefore("</h2>")

        assertThat(
            heading,
        ).contains("href=\"https://notion.so/database\"", "target=\"_blank\"", "rel=\"noopener noreferrer\"", ">Original database</a>")
        assertThat(heading).doesNotContain("<span>")
    }

    @Test
    fun `keeps the original database link when every view contains only an unavailable placeholder`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val resolutions = mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl))

        listOf(1, 2).forEach { viewCount ->
            val tabs =
                (1..viewCount).map { index ->
                    BlockNode(
                        BlockId("view-$index"),
                        LayoutBlockContent.TabItem(listOf(InlineContent.Text("Unavailable view $index")), null),
                        children = listOf(node("view-$index-unavailable", UnsupportedBlockContent("database_view"))),
                    )
                }
            val database =
                BlockNode(
                    BlockId("database-fallback"),
                    ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Original database"),
                    children = listOf(BlockNode(BlockId("database-views"), LayoutBlockContent.TabContainer, children = tabs)),
                )

            val html = render(page(listOf(database), resolutions))
            val heading = html.substringAfter("class=\"notion-database-title\"").substringBefore("</h2>")

            assertThat(heading)
                .describedAs("%s unavailable views", viewCount)
                .contains(
                    "href=\"https://notion.so/database\"",
                    "target=\"_blank\"",
                    "rel=\"noopener noreferrer\"",
                    ">Original database</a>",
                )
            assertThat(html).contains("Unsupported block: database_view")
        }
    }

    @Test
    fun `keeps the database heading plain when an available view accompanies a placeholder`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val tabs =
            listOf(
                BlockNode(
                    BlockId("unavailable-view"),
                    LayoutBlockContent.TabItem(listOf(InlineContent.Text("Unavailable")), null),
                    children = listOf(node("unavailable-content", UnsupportedBlockContent("database_view"))),
                ),
                BlockNode(
                    BlockId("available-view"),
                    LayoutBlockContent.TabItem(listOf(InlineContent.Text("Available")), null),
                    children = listOf(node("available-table", dataTable("Available", listOf("Title"), emptyList()))),
                ),
            )
        val database =
            BlockNode(
                BlockId("database"),
                ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Blog posts"),
                children = listOf(BlockNode(BlockId("database-views"), LayoutBlockContent.TabContainer, children = tabs)),
            )

        val html =
            render(
                page(
                    listOf(database),
                    mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl)),
                ),
            )
        val heading = html.substringAfter("class=\"notion-database-title\"").substringBefore("</h2>")

        assertThat(heading).contains("<span>Blog posts</span>").doesNotContain("<a", "href=")
        assertThat(html).contains("Unsupported block: database_view", "No published rows in this view.")
    }
}
