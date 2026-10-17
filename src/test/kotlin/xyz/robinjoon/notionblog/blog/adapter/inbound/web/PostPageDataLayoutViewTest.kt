package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.assembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.clock
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.render
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.DataGalleryView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.DataListView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ListView
import xyz.robinjoon.notionblog.blog.application.model.LinkResolution
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.net.URI
import java.util.UUID

class PostPageDataLayoutViewTest {
    @Test
    fun `renders member row links internally and outside rows externally in every database layout`() {
        val internal = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion"), "member-row"), URI("https://notion.so/member-row"))
        val external = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion"), "outside-row"), URI("https://notion.so/outside-row"))
        val postId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000123"))
        val data =
            DataSet(
                "Blog posts",
                listOf(DataColumn("Title")),
                listOf(
                    DataRow(listOf(listOf(InlineContent.Text("Member article", link = internal))), link = internal),
                    DataRow(listOf(listOf(InlineContent.Text("Outside article", link = external))), link = external),
                ),
                titleColumnIndex = 0,
            )
        val resolutions =
            mapOf(
                internal to LinkResolution.Internal(postId),
                external to LinkResolution.External(checkNotNull(external.originalUrl)),
            )

        listOf(DataViewContent.Table(data), DataViewContent.ListView(data), DataViewContent.Gallery(data)).forEach { layout ->
            val html = render(page(listOf(node("view", layout)), resolutions))
            val internalAnchor = Regex("<a\\b[^>]*href=\"/posts/${postId.value}\"[^>]*>").find(html)?.value
            val externalAnchor = Regex("<a\\b[^>]*href=\"https://notion.so/outside-row\"[^>]*>").find(html)?.value

            assertThat(internalAnchor).describedAs(layout.javaClass.simpleName).isNotNull().doesNotContain("target=", "rel=")
            assertThat(
                externalAnchor,
            ).describedAs(layout.javaClass.simpleName).isNotNull().contains("target=\"_blank\"", "rel=\"noopener noreferrer\"")
            assertThat(html).contains("Member article", "Outside article").doesNotContain("href=\"https://notion.so/member-row\"")
            assertThat(Regex("href=\"/posts/${postId.value}\"").findAll(html).count()).isEqualTo(1)
            assertThat(Regex("href=\"https://notion.so/outside-row\"").findAll(html).count()).isEqualTo(1)
            assertThat(Regex("<a\\b[^>]*>(?:(?!</a>).)*<a\\b", RegexOption.DOT_MATCHES_ALL).containsMatchIn(html)).isFalse()
        }
    }

    @Test
    fun `renders database lists as named entries with properties and safe non nested title links`() {
        val rowLink = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion"), "row"), URI("https://example.com/row"))
        val data =
            DataSet(
                "Reading list <script>",
                listOf(DataColumn("Status"), DataColumn("Name"), DataColumn("Reference")),
                listOf(
                    DataRow(
                        listOf(
                            listOf(InlineContent.Text("Published")),
                            listOf(
                                InlineContent.Text(
                                    "Article <img>",
                                    TextAnnotations(bold = true),
                                    LinkTarget.ExternalUrl(URI("https://example.com/old-title-link")),
                                ),
                            ),
                            listOf(InlineContent.Text("Documentation", link = LinkTarget.ExternalUrl(URI("https://example.com/docs")))),
                        ),
                        link = rowLink,
                        icon = BlockIcon.Emoji("📚"),
                    ),
                ),
                titleColumnIndex = 1,
            )
        val input =
            page(
                listOf(node("reading-list", DataViewContent.ListView(data))),
                mapOf(rowLink to LinkResolution.Internal(PostId(UUID.fromString("00000000-0000-0000-0000-000000000123")))),
            )
        val view =
            assembler
                .assemble(input)
                .post.blocks
                .single() as DataListView
        val html = render(input)

        assertThat(
            view.rows
                .single()
                .properties
                .map { it.name },
        ).containsExactly("Status", "Reference")
        assertThat(
            html,
        ).contains("notion-data-list", "<ul", "<li", "<dl", "<dt>Status</dt>", "Article &lt;img&gt;", "Reading list &lt;script&gt;", "📚")
        assertThat(html).contains("href=\"/posts/00000000-0000-0000-0000-000000000123\"", "href=\"https://example.com/docs\"")
        assertThat(html).doesNotContain("notion-data-table", "<table", "<dt>Name</dt>", "old-title-link", "<script", "<img>")
        assertThat(Regex("<a\\b[^>]*>(?:(?!</a>).)*<a\\b", RegexOption.DOT_MATCHES_ALL).containsMatchIn(html)).isFalse()
    }

    @Test
    fun `renders gallery card options and only safe unexpired covers`() {
        val data =
            DataSet(
                "Gallery",
                listOf(DataColumn("Name"), DataColumn("Description")),
                listOf(
                    DataRow(
                        listOf(listOf(InlineContent.Text("Visible cover")), listOf(InlineContent.Text("A description"))),
                        cover = MediaSource.External(URI("https://example.com/cover.png")),
                    ),
                    DataRow(
                        listOf(listOf(InlineContent.Text("Expired cover")), emptyList()),
                        cover = MediaSource.SourceHosted(URI("https://example.com/expired.png"), clock.instant()),
                    ),
                    DataRow(
                        listOf(listOf(InlineContent.Text("Unsafe cover")), emptyList()),
                        cover = MediaSource.External(URI("javascript:alert(1)")),
                    ),
                    DataRow(listOf(emptyList(), emptyList()), link = LinkTarget.ExternalUrl(URI("javascript:alert(1)"))),
                ),
                titleColumnIndex = 0,
            )
        val gallery = DataViewContent.Gallery(data, DataGalleryOptions(DataCardSize.LARGE, DataCoverAspect.CONTAIN, DataCardLayout.COMPACT))
        val input = page(listOf(node("gallery", gallery)))
        val view =
            assembler
                .assemble(input)
                .post.blocks
                .single() as DataGalleryView
        val html = render(input)

        assertThat(view.rows.map { it.coverUrl }).containsExactly("https://example.com/cover.png", null, null, null)
        assertThat(
            html,
        ).contains(
            "notion-data-gallery",
            "notion-data-gallery-large",
            "notion-data-gallery-contain",
            "notion-data-gallery-compact",
            "notion-data-card",
        )
        assertThat(
            html,
        ).contains("src=\"https://example.com/cover.png\"", "alt=\"Visible cover\"", "loading=\"lazy\"", "Untitled", "Description")
        assertThat(html).doesNotContain("expired.png", "javascript:", "<table", "<dt>Name</dt>", "style=")
        assertThat(Regex("<img\\b").findAll(html).count()).isEqualTo(1)
    }

    @Test
    fun `uses neutral row titles when the visible data has no title column and shows empty list and gallery states`() {
        val data =
            DataSet(
                "Untitled rows",
                listOf(DataColumn("Status")),
                listOf(DataRow(listOf(listOf(InlineContent.Text("Ready"))), link = LinkTarget.ExternalUrl(URI("https://example.com/row")))),
            )
        val empty = DataSet("Empty", listOf(DataColumn("Status")), emptyList())
        val input =
            page(
                listOf(
                    node("no-title", DataViewContent.ListView(data)),
                    node("empty-list", DataViewContent.ListView(empty)),
                    node("empty-gallery", DataViewContent.Gallery(empty)),
                ),
            )
        val list =
            assembler
                .assemble(input)
                .post.blocks
                .first() as DataListView
        val html = render(input)

        assertThat(list.rows.single().titleText).isEqualTo("Untitled")
        assertThat(
            list.rows
                .single()
                .properties
                .single()
                .name,
        ).isEqualTo("Status")
        assertThat(html).contains("Untitled", "href=\"https://example.com/row\"", "<dt>Status</dt>")
        assertThat(Regex("No published rows in this view\\.").findAll(html).count()).isEqualTo(2)
    }
}
