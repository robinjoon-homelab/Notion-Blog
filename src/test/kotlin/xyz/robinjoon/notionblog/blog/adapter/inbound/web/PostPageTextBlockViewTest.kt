package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.thymeleaf.context.Context
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.assembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.render
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.templateEngine
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ListView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ParagraphView
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.NumberedListFormat
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.style.BlockStyle
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken
import xyz.robinjoon.notionblog.blog.domain.post.block.style.StyleVariant
import xyz.robinjoon.notionblog.blog.domain.post.block.style.WidthToken
import java.net.URI

class PostPageTextBlockViewTest {
    @Test
    fun `maps style tokens through fixed view classes instead of user supplied values`() {
        val page =
            page(
                nodes =
                    listOf(
                        BlockNode(
                            BlockId("styled"),
                            TextBlockContent.Paragraph(listOf(InlineContent.Text("safe"))),
                            BlockStyle(foreground = ColorToken.RED, variant = StyleVariant("untrusted variant")),
                        ),
                    ),
            )

        val view = assembler.assemble(page)
        val paragraph = view.post.blocks.single() as ParagraphView

        assertThat(paragraph.style.classes).contains("notion-color-red")
        assertThat(paragraph.style.classes).doesNotContain("untrusted variant")
    }

    @Test
    fun `renders semantic list toggle table tabs and escaped content through thymeleaf`() {
        val page =
            page(
                nodes =
                    listOf(
                        BlockNode(
                            BlockId("parent-paragraph"),
                            TextBlockContent.Paragraph(listOf(InlineContent.Text("parent"))),
                            children = listOf(node("paragraph-child", TextBlockContent.Paragraph(listOf(InlineContent.Text("child"))))),
                        ),
                        node("bullet-1", ListBlockContent.BulletedItem(listOf(InlineContent.Text("one")))),
                        node("bullet-2", ListBlockContent.BulletedItem(listOf(InlineContent.Text("two")))),
                        node("todo", ListBlockContent.ToDoItem(listOf(InlineContent.Text("task")), true)),
                        BlockNode(
                            BlockId("toggle"),
                            TextBlockContent.Toggle(listOf(InlineContent.Text("toggle"))),
                            children = listOf(node("toggle-child", TextBlockContent.Paragraph(listOf(InlineContent.Text("nested"))))),
                        ),
                        BlockNode(
                            BlockId("table"),
                            LayoutBlockContent.Table(width = 2, hasColumnHeader = true, hasRowHeader = true),
                            children =
                                listOf(
                                    BlockNode(
                                        BlockId("header-row"),
                                        LayoutBlockContent.TableRow(
                                            listOf(listOf(InlineContent.Text("name")), listOf(InlineContent.Text("value"))),
                                        ),
                                    ),
                                    BlockNode(
                                        BlockId("body-row"),
                                        LayoutBlockContent.TableRow(
                                            listOf(listOf(InlineContent.Text("row")), listOf(InlineContent.Text("cell"))),
                                        ),
                                    ),
                                ),
                        ),
                        BlockNode(
                            BlockId("columns"),
                            LayoutBlockContent.ColumnList,
                            children =
                                listOf(
                                    BlockNode(
                                        BlockId("column"),
                                        LayoutBlockContent.Column(WidthToken(0.53)),
                                        children =
                                            listOf(
                                                node("column-text", TextBlockContent.Paragraph(listOf(InlineContent.Text("column")))),
                                            ),
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
                                        LayoutBlockContent.TabItem(listOf(InlineContent.Text("Tab")), null),
                                        children =
                                            listOf(
                                                node("tab-text", TextBlockContent.Paragraph(listOf(InlineContent.Text("panel")))),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

        val html = templateEngine().process("blog/post", Context().apply { setVariable("page", assembler.assemble(page)) })

        assertThat(html).contains("<ul", "one", "two", "type=\"checkbox\"", " disabled")
        assertThat(html).contains("<details", "toggle", "<thead", "scope=\"col\"", "scope=\"row\"")
        assertThat(html).contains("notion-columns", "notion-column-width-55", "role=\"tablist\"", "role=\"tabpanel\"")
        assertThat(html).doesNotMatch(Regex("(?s)<p[^>]*>(?:(?!</p>).)*<section class=\"notion-children\"").toPattern())
        assertThat(html).doesNotContain("style=")
        assertThat(html).doesNotContain("th:utext")
    }

    @Test
    fun `starts a new ordered list only at an explicit numbered list boundary`() {
        val rich = listOf(InlineContent.Text("item"))
        val page =
            page(
                nodes =
                    listOf(
                        node("roman-3", ListBlockContent.NumberedItem(rich, 3, NumberedListFormat.UPPER_ROMAN, true)),
                        node("roman-4", ListBlockContent.NumberedItem(rich)),
                        node("alpha-7", ListBlockContent.NumberedItem(rich, 7, NumberedListFormat.LOWER_ALPHA, true)),
                        node("alpha-8", ListBlockContent.NumberedItem(rich)),
                    ),
            )

        val lists =
            assembler
                .assemble(page)
                .post.blocks
                .filterIsInstance<ListView>()

        assertThat(lists).hasSize(2)
        assertThat(lists[0].startNumber).isEqualTo(3)
        assertThat(
            lists[0].numberFormat,
        ).isEqualTo(xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NumberedListFormatView.UPPER_ROMAN)
        assertThat(lists[0].items).hasSize(2)
        assertThat(lists[1].startNumber).isEqualTo(7)
        assertThat(
            lists[1].numberFormat,
        ).isEqualTo(xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NumberedListFormatView.LOWER_ALPHA)
        assertThat(lists[1].items).hasSize(2)

        val html = render(page)
        assertThat(html).contains("<ol", "start=\"3\"", "type=\"I\"", "start=\"7\"", "type=\"a\"")
    }

    @Test
    fun `renders every trusted icon kind and keeps equation source as progressive fallback`() {
        val rich = listOf(InlineContent.Text("content"), InlineContent.Equation("x < y"))
        val page =
            page(
                nodes =
                    listOf(
                        node("emoji", TextBlockContent.Callout(rich, BlockIcon.Emoji("💡"))),
                        node(
                            "media",
                            TextBlockContent.Callout(
                                rich,
                                BlockIcon.Media(MediaSource.External(URI("https://example.com/icon.png"))),
                            ),
                        ),
                        node("native", TextBlockContent.Callout(rich, BlockIcon.Native("academic-cap", ColorToken.BLUE))),
                        BlockNode(
                            BlockId("tabs"),
                            LayoutBlockContent.TabContainer,
                            children =
                                listOf(
                                    BlockNode(
                                        BlockId("custom"),
                                        LayoutBlockContent.TabItem(
                                            listOf(InlineContent.Text("Custom tab")),
                                            BlockIcon.CustomEmoji(
                                                "emoji-id",
                                                "party-parrot",
                                                MediaSource.External(URI("https://example.com/custom.gif")),
                                            ),
                                        ),
                                        children = listOf(node("panel", TextBlockContent.Equation("E = mc^2"))),
                                    ),
                                ),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).contains(
            "notion-callout-icon",
            "💡",
            "https://example.com/icon.png",
            "notion-native-icon",
            "academic-cap",
            "notion-color-blue",
            "notion-tab-icon",
            "https://example.com/custom.gif",
            "notion-math-inline",
            "notion-math-block",
            "data-expression=\"E = mc^2\"",
        )
        assertThat(html).contains("x &lt; y", "E = mc^2")
        assertThat(html).doesNotContain("x < y")
    }

    @Test
    fun `renders code content literally without template whitespace`() {
        val source = "if (left < right && ready) {\n    return value\n}"
        val page =
            page(
                nodes =
                    listOf(
                        node("code", TextBlockContent.Code(listOf(InlineContent.Text(source)), "kotlin", emptyList())),
                    ),
            )

        val html = render(page)
        val codeMarkup = html.substringAfter("<pre><code>").substringBefore("</code></pre>")

        assertThat(codeMarkup).isEqualTo("if (left &lt; right &amp;&amp; ready) {\n    return value\n}")
        assertThat(codeMarkup).doesNotContain("<span")
    }
}
