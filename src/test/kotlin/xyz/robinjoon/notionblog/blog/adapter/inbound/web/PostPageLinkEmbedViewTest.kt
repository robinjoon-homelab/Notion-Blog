package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.assembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.clock
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.render
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ExternalLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.InternalLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MediaView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ParagraphView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.TextInlineView
import xyz.robinjoon.notionblog.blog.application.model.LinkResolution
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.MentionKind
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken
import java.net.URI
import java.util.UUID

class PostPageLinkEmbedViewTest {
    @Test
    fun `renders only resolved internal or safe external links and expires source hosted media`() {
        val document = SourceDocumentRef(SourceId("notion"), "internal")
        val internal = LinkTarget.SourceDocument(document, URI("https://notion.so/internal"))
        val external = LinkTarget.ExternalUrl(URI("https://example.com/read"))
        val unsafe = LinkTarget.ExternalUrl(URI("javascript:alert(1)"))
        val page =
            page(
                nodes =
                    listOf(
                        node(
                            "paragraph",
                            TextBlockContent.Paragraph(
                                listOf(
                                    InlineContent.Text(
                                        "<script>alert(1)</script>",
                                        TextAnnotations(bold = true, foreground = ColorToken.RED, background = ColorToken.BLUE),
                                        internal,
                                    ),
                                    InlineContent.Text("external", link = external),
                                    InlineContent.Text("unsafe", link = unsafe),
                                    InlineContent.Equation("x &lt; y"),
                                    InlineContent.Mention("Person", MentionKind.USER, target = external),
                                ),
                            ),
                        ),
                        node(
                            "expired-image",
                            MediaBlockContent.Media(
                                MediaType.IMAGE,
                                MediaSource.SourceHosted(URI("https://files.notion.so/image"), clock.instant().minusSeconds(1)),
                                "image.png",
                            ),
                        ),
                        node(
                            "unsafe-image",
                            MediaBlockContent.Media(MediaType.IMAGE, MediaSource.External(URI("data:image/png;base64,x")), "image.png"),
                        ),
                    ),
                links = mapOf(internal to LinkResolution.Internal(PostId(UUID.fromString("00000000-0000-0000-0000-000000000123")))),
            )

        val view = assembler.assemble(page)
        val paragraph = view.post.blocks.first() as ParagraphView
        val text = paragraph.content.filterIsInstance<TextInlineView>()

        assertThat((checkNotNull(text[0].link) as InternalLinkView).href).isEqualTo("/posts/00000000-0000-0000-0000-000000000123")
        assertThat((checkNotNull(text[1].link) as ExternalLinkView).href).isEqualTo("https://example.com/read")
        assertThat(text[2].link).isNull()
        assertThat(text[0].annotations.classes).contains("notion-color-red", "notion-background-blue")
        assertThat((view.post.blocks[1] as MediaView).url).isNull()
        assertThat((view.post.blocks[2] as MediaView).url).isNull()
    }

    @Test
    fun `renders linked and unlinked inline content exactly once`() {
        val page =
            page(
                nodes =
                    listOf(
                        node(
                            "paragraph",
                            TextBlockContent.Paragraph(
                                listOf(
                                    InlineContent.Text(
                                        "unique-linked-inline",
                                        link = LinkTarget.ExternalUrl(URI("https://example.com/read")),
                                    ),
                                    InlineContent.Text("unique-plain-inline"),
                                ),
                            ),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).containsOnlyOnce("unique-linked-inline")
        assertThat(html).containsOnlyOnce("unique-plain-inline")
        assertThat(html).contains("href=\"https://example.com/read\"", "target=\"_blank\"", "rel=\"noopener noreferrer\"")
    }

    @Test
    fun `canonicalizes supported embeds and degrades unsupported providers to a safe link`() {
        val caption = listOf(InlineContent.Text("Watch externally"))
        val page =
            page(
                nodes =
                    listOf(
                        node("youtube", MediaBlockContent.Embed(URI("http://www.youtube.com/watch?v=abc_123"), emptyList())),
                        node("vimeo", MediaBlockContent.Embed(URI("https://vimeo.com/987654321?autoplay=1"), emptyList())),
                        node("unknown", MediaBlockContent.Embed(URI("https://widgets.example.com/card?id=42"), caption)),
                    ),
            )

        val html = render(page)

        assertThat(html).contains(
            "src=\"https://www.youtube-nocookie.com/embed/abc_123\"",
            "src=\"https://player.vimeo.com/video/987654321\"",
            "href=\"https://widgets.example.com/card?id=42\"",
            ">Open embedded content</a>",
            "Watch externally",
            "sandbox=\"allow-scripts allow-same-origin allow-popups allow-presentation\"",
            "allowfullscreen",
            "referrerpolicy=\"strict-origin-when-cross-origin\"",
        )
        assertThat(Regex("<iframe\\b").findAll(html).count()).isEqualTo(2)
        assertThat(html).doesNotContain("src=\"http://")
    }

    @Test
    fun `uses figure captions only for figure content`() {
        val caption = listOf(InlineContent.Text("Description"))
        val page =
            page(
                nodes =
                    listOf(
                        node("bookmark", MediaBlockContent.Bookmark(URI("https://example.com/read"), caption)),
                        node(
                            "image",
                            MediaBlockContent.Media(
                                MediaType.IMAGE,
                                MediaSource.External(URI("https://example.com/image.png")),
                                "image.png",
                                caption,
                            ),
                        ),
                    ),
            )

        val html = render(page)
        val bookmarkMarkup = html.substringAfter("id=\"bookmark\"").substringBefore("</aside>")
        val imageMarkup = html.substringAfter("id=\"image\"").substringBefore("</figure>")

        assertThat(bookmarkMarkup).contains("class=\"notion-caption\"").doesNotContain("<figcaption")
        assertThat(imageMarkup).contains("<figcaption", "Description")
    }

    @Test
    fun `keeps figure captions as the last child when figure blocks have nested content`() {
        val caption = listOf(InlineContent.Text("Description"))
        val page =
            page(
                nodes =
                    listOf(
                        BlockNode(
                            BlockId("code"),
                            TextBlockContent.Code(emptyList(), "kotlin", caption),
                            children = listOf(node("code-nested", TextBlockContent.Paragraph(listOf(InlineContent.Text("Nested"))))),
                        ),
                        BlockNode(
                            BlockId("image"),
                            MediaBlockContent.Media(
                                MediaType.IMAGE,
                                MediaSource.External(URI("https://example.com/image.png")),
                                "image.png",
                                caption,
                            ),
                            children = listOf(node("image-nested", TextBlockContent.Paragraph(listOf(InlineContent.Text("Nested"))))),
                        ),
                        BlockNode(
                            BlockId("embed"),
                            MediaBlockContent.Embed(URI("https://vimeo.com/123"), caption),
                            children = listOf(node("embed-nested", TextBlockContent.Paragraph(listOf(InlineContent.Text("Nested"))))),
                        ),
                    ),
            )

        val html = render(page)

        listOf("code", "image", "embed").forEach { id ->
            val figure = html.substringAfter("id=\"$id\"").substringBefore("</figure>")
            assertThat(figure.substringAfter("<figcaption")).doesNotContain("notion-children")
        }
    }
}
