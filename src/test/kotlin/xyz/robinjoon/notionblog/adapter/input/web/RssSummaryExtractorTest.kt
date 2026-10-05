package xyz.robinjoon.notionblog.adapter.input.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.inline.MentionKind
import java.net.URI
import java.util.UUID

class RssSummaryExtractorTest {
    private val extractor = RssSummaryExtractor()

    @Test
    fun `allowed text blocks and their children follow document and inline run order`() {
        val richText = listOf(
            InlineContent.Text("read", link = LinkTarget.ExternalUrl(URI("https://private.example/target"))),
            InlineContent.Mention("Alice", MentionKind.USER),
            InlineContent.Equation("x+y"),
        )
        val post = post(
            node("paragraph", TextBlockContent.Paragraph(richText)),
            node("heading", TextBlockContent.Heading(HeadingLevel.TWO, text("heading"))),
            node(
                "toggle",
                TextBlockContent.Toggle(text("toggle")),
                node("nested", TextBlockContent.Paragraph(text("nested"))),
            ),
            node("quote", TextBlockContent.Quote(text("quote"))),
            node("callout", TextBlockContent.Callout(text("callout"), null)),
            node("bullet", ListBlockContent.BulletedItem(text("bullet"))),
            node("number", ListBlockContent.NumberedItem(text("number"))),
            node("todo", ListBlockContent.ToDoItem(text("todo"), true)),
        )

        assertThat(extractor.extract(post)).isEqualTo("readAlicex+y heading toggle nested quote callout bullet number todo")
    }

    @Test
    fun `code database cells media links and unsupported content never enter the summary`() {
        val post = post(
            node("code", TextBlockContent.Code(text("code secret"), "kotlin", text("code caption"))),
            node("equation", TextBlockContent.Equation("block equation")),
            node("bookmark", MediaBlockContent.Bookmark(URI("https://private.example/media"), text("media caption"))),
            node(
                "database",
                DataViewContent.Table(
                    DataSet("database title", listOf(DataColumn("column")), listOf(DataRow(listOf(text("database secret"))))),
                ),
            ),
            node(
                "unsupported",
                UnsupportedBlockContent("unsupported secret"),
                node("child", TextBlockContent.Paragraph(text("visible child"))),
            ),
        )

        assertThat(extractor.extract(post)).isEqualTo("visible child")
    }

    @Test
    fun `XML forbidden characters are removed and whitespace including NBSP collapses before limiting`() {
        val value = " \tfirst\u00a0\u00a0second\n\r third\u0000\u0001\u000B\u000C\u001F\uFFFE\uFFFF\uD800 end\uDC00 😀  "

        assertThat(extractor.extract(post(node("text", TextBlockContent.Paragraph(text(value))))))
            .isEqualTo("first second third end 😀")
    }

    @ParameterizedTest
    @ValueSource(ints = [279, 280, 281])
    fun `summary limit counts Unicode code points and never cuts an emoji`(length: Int) {
        val value = "😀".repeat(length)
        val result = extractor.extract(post(node("text", TextBlockContent.Paragraph(text(value)))))

        assertThat(result).isEqualTo(if (length <= 280) value else "😀".repeat(279) + "…")
        assertThat(result.codePointCount(0, result.length)).isEqualTo(minOf(length, 280))
    }

    @Test
    fun `summary length is measured before HTML and XML escaping`() {
        val value = "<&".repeat(150)

        assertThat(extractor.extract(post(node("text", TextBlockContent.Paragraph(text(value))))))
            .isEqualTo(value.take(279) + "…")
    }

    @Test
    fun `empty or excluded body falls back to a cleaned and limited title`() {
        val result = extractor.extract(post(title = " \u0001" + "😀".repeat(281) + "\uFFFF "))

        assertThat(result).isEqualTo("😀".repeat(279) + "…")
        assertThat(extractor.extract(post(title = "\u0000\uD800\uFFFF"))).isEqualTo("제목 없는 글")
    }

    private fun post(vararg roots: BlockNode, title: String = "Fallback title") = Post(
        PostId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        title,
        BlockTree(roots.toList()),
    )

    private fun node(id: String, content: BlockContent, vararg children: BlockNode) = BlockNode(BlockId(id), content, children = children.toList())

    private fun text(value: String) = listOf(InlineContent.Text(value))
}
