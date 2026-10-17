package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.assembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.dataTable
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.render
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.DataTableView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.TextInlineView
import xyz.robinjoon.notionblog.blog.application.model.LinkResolution
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken
import java.net.URI
import java.util.UUID

class PostPageDataTableViewTest {
    @Test
    fun `renders database table captions columns and rich cells with safe resolved links`() {
        val reference = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion"), "public-row"), URI("https://notion.so/public-row"))
        val page =
            page(
                nodes =
                    listOf(
                        node(
                            "database-table",
                            dataTable(
                                title = "Roadmap <script>unsafe</script>",
                                columns = listOf("Name <img src=x onerror=alert(1)>", "Status & notes"),
                                rows =
                                    listOf(
                                        DataRow(
                                            listOf(
                                                listOf(InlineContent.Text("Published item", TextAnnotations(bold = true), reference)),
                                                listOf(
                                                    InlineContent.Text("Ready", TextAnnotations(background = ColorToken.GREEN)),
                                                    InlineContent.Text(
                                                        "Documentation",
                                                        link = LinkTarget.ExternalUrl(URI("https://example.com/docs")),
                                                    ),
                                                ),
                                            ),
                                        ),
                                        DataRow(
                                            listOf(
                                                listOf(
                                                    InlineContent.Text(
                                                        "<script>alert(1)</script>",
                                                        link = LinkTarget.ExternalUrl(URI("javascript:alert(1)")),
                                                    ),
                                                ),
                                                emptyList(),
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                    ),
                links = mapOf(reference to LinkResolution.Internal(PostId(UUID.fromString("00000000-0000-0000-0000-000000000123")))),
            )

        val view =
            assembler
                .assemble(page)
                .post.blocks
                .single() as DataTableView
        val html = render(page)
        val table = html.substringAfter("<table class=\"notion-table notion-data-table\"").substringBefore("</table>")

        assertThat(view.title).isEqualTo("Roadmap <script>unsafe</script>")
        assertThat(view.columns.map { it.name }).containsExactly("Name <img src=x onerror=alert(1)>", "Status & notes")
        assertThat(view.rows).hasSize(2)
        assertThat((view.rows[0].cells[0].single() as TextInlineView).annotations.bold).isTrue()
        assertThat((view.rows[1].cells[0].single() as TextInlineView).link).isNull()
        assertThat(
            html,
        ).contains("notion-data-table-wrapper", "role=\"region\"", "tabindex=\"0\"", "aria-labelledby=\"database-table-caption\"")
        assertThat(
            table,
        ).contains("class=\"notion-sr-only\"", "Roadmap &lt;script&gt;unsafe&lt;/script&gt;</caption>", "<thead>", "<tbody>")
        assertThat(Regex("scope=\"col\"").findAll(table).count()).isEqualTo(2)
        assertThat(Regex("<tr>").findAll(table.substringAfter("<tbody>")).count()).isEqualTo(2)
        assertThat(table).contains(
            "Name &lt;img src=x onerror=alert(1)&gt;",
            "Status &amp; notes",
            "href=\"/posts/00000000-0000-0000-0000-000000000123\"",
            "notion-background-green",
            "href=\"https://example.com/docs\"",
            "rel=\"noopener noreferrer\"",
            "&lt;script&gt;alert(1)&lt;/script&gt;",
        )
        assertThat(table).doesNotContain("<script", "<img", "href=\"javascript:", "style=", "data-source", "property-id")
    }

    @Test
    fun `renders an accessible empty database result without inventing rows`() {
        val html = render(page(listOf(node("empty-database", dataTable("Published tasks", listOf("Task", "Owner"), emptyList())))))
        val table = html.substringAfter("<table class=\"notion-table notion-data-table\"").substringBefore("</table>")

        assertThat(table).contains("class=\"notion-sr-only\"", "Published tasks</caption>", ">Task</div>", ">Owner</div>")
        assertThat(table).contains("colspan=\"2\"", "notion-data-table-empty", "No published rows in this view.")
        assertThat(table.substringAfter("<tbody>")).doesNotContain("notion-data-table-cell")
    }

    @Test
    fun `renders table width wrapping vertical line and frozen column options without source CSS`() {
        val data =
            DataSet(
                "Readable table",
                listOf(DataColumn("Title", 201, false), DataColumn("Notes", 900, true), DataColumn("Default", 0), DataColumn("Small", 1)),
                listOf(
                    DataRow(
                        List(4) {
                            listOf(InlineContent.Text("A long value"))
                        },
                        link = LinkTarget.ExternalUrl(URI("https://example.com/row")),
                        icon = BlockIcon.Emoji("📚"),
                    ),
                ),
                titleColumnIndex = 0,
            )
        val table = DataViewContent.Table(data, DataTableOptions(wrapCells = false, frozenColumns = 2, showVerticalLines = false))
        val view =
            assembler
                .assemble(page(listOf(node("configured-table", table))))
                .post.blocks
                .single() as DataTableView
        val html = render(page(listOf(node("configured-table", table))))

        assertThat(
            view.columns.map {
                it.widthClass.cssClass
            },
        ).containsExactly("notion-data-width-200", "notion-data-width-640", "notion-data-width-200", "notion-data-width-80")
        assertThat(view.columns.map { it.wrap }).containsExactly(false, true, false, false)
        assertThat(view.columns.map { it.frozen }).containsExactly(true, true, false, false)
        assertThat(
            html,
        ).contains(
            "data-frozen-columns=\"2\"",
            "notion-data-table-no-vertical-lines",
            "notion-data-nowrap",
            "notion-data-wrap",
            "notion-data-width-640",
            "aria-label=\"Title\"",
        )
        assertThat(Regex("href=\"https://example.com/row\"").findAll(html).count()).isEqualTo(1)
        assertThat(Regex("📚").findAll(html).count()).isEqualTo(1)
        assertThat(html).doesNotContain("style=", "width=\"900", "<script>")
    }
}
