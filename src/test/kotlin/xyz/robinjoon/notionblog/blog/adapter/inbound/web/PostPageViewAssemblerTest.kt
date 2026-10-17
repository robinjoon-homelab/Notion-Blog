package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.thymeleaf.context.Context
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.clock
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.render
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.templateEngine
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.HeadingLevel
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import java.util.UUID

class PostPageViewAssemblerTest {
    @ParameterizedTest
    @CsvSource("true, true", "true, false", "false, true", "false, false")
    fun `renders RSS discovery and subscription only with a configured feed regardless of footer document`(
        configuredFeed: Boolean,
        footerDocument: Boolean,
    ) {
        val feedUrl = "https://blog.example:8443/feed.xml".takeIf { configuredFeed }
        val footer =
            WebFixtures
                .revision(
                    title = "Site footer",
                    nodes = listOf(node("footer-content", TextBlockContent.Paragraph(listOf(InlineContent.Text("Footer text"))))),
                    postId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000004")),
                ).takeIf { footerDocument }
        val base = page(emptyList())
        val page = base.copy(chrome = base.chrome.copy(footer = footer))
        val view = PostPageViewAssembler(clock, feedUrl).assemble(page)

        val html = templateEngine().process("blog/post", Context().apply { setVariable("page", view) })
        val head = html.substringAfter("<head>").substringBefore("</head>")
        val body = html.substringAfter("<body>").substringBefore("</body>")
        val alternateLinks = Regex("<link\\b[^>]*rel=\"alternate\"[^>]*>").findAll(head).map { it.value }.toList()
        val subscriptionLinks = Regex("<a\\b[^>]*>\\s*RSS 구독\\s*</a>").findAll(body).map { it.value }.toList()

        assertThat(view.chrome.feedUrl).isEqualTo(feedUrl)
        if (configuredFeed) {
            assertThat(alternateLinks).hasSize(1)
            assertThat(alternateLinks.single()).contains("type=\"application/rss+xml\"", "href=\"$feedUrl\"")
            assertThat(subscriptionLinks).hasSize(1)
            assertThat(subscriptionLinks.single()).contains("href=\"$feedUrl\"")
            assertThat(body.indexOf(subscriptionLinks.single())).isGreaterThan(body.indexOf("</article>"))
        } else {
            assertThat(alternateLinks).isEmpty()
            assertThat(subscriptionLinks).isEmpty()
            assertThat(html).doesNotContain("application/rss+xml", "RSS 구독")
        }
        assertThat(body.contains("Footer text")).isEqualTo(footerDocument)
    }

    @Test
    fun `keeps repeated header body and footer document ids and navigation references distinct`() {
        val base =
            page(
                listOf(
                    node("heading", TextBlockContent.Heading(HeadingLevel.TWO, listOf(InlineContent.Text("Section")))),
                    node("toc", ReferenceBlockContent.TableOfContents),
                    node("item", ListBlockContent.BulletedItem(listOf(InlineContent.Text("Item")))),
                    BlockNode(
                        BlockId("tabs"),
                        LayoutBlockContent.TabContainer,
                        children =
                            listOf(
                                BlockNode(
                                    BlockId("tab"),
                                    LayoutBlockContent.TabItem(listOf(InlineContent.Text("Tab")), null),
                                    children = listOf(node("panel-text", TextBlockContent.Paragraph(listOf(InlineContent.Text("Panel"))))),
                                ),
                            ),
                    ),
                ),
            )
        val html = render(base.copy(chrome = base.chrome.copy(header = base.post.revision, footer = base.post.revision)))
        val idPattern = Regex("""\bid="([^"]+)"""")
        val fragmentPattern = Regex("""href="#([^"]+)"""")
        val ariaReferencePattern = Regex("""aria-(?:controls|labelledby)="([^"]+)"""")
        val ids = idPattern.findAll(html).map { it.groupValues[1] }.toList()

        assertThat(ids).doesNotHaveDuplicates()
        assertThat(html.substringAfter("<article").substringBefore("</article>"))
            .contains("id=\"heading\"", "href=\"#heading\"")
        listOf("header", "article", "footer").forEach { element ->
            val region = html.substringAfter("<$element").substringBefore("</$element>")
            val regionIds = idPattern.findAll(region).map { it.groupValues[1] }.toList()
            val fragments = fragmentPattern.findAll(region).map { it.groupValues[1] }.toList()
            val ariaReferences = ariaReferencePattern.findAll(region).map { it.groupValues[1] }.toList()

            assertThat(fragments).hasSize(2)
            assertThat(ariaReferences).hasSize(2)
            assertThat(regionIds).containsAll(fragments).containsAll(ariaReferences)
        }
        assertThat(
            base.post.revision.content.roots
                .first()
                .id.value,
        ).isEqualTo("heading")
    }
}
