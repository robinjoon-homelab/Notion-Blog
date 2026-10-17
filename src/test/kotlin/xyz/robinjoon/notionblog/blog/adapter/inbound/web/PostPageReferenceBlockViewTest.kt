package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.render
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MeetingNotesStatus
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.SynchronizedBlockOrigin
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent

class PostPageReferenceBlockViewTest {
    @Test
    fun `derives an accessible breadcrumb and renders database and template titles with children`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val page =
            page(
                nodes =
                    listOf(
                        node("breadcrumb", ReferenceBlockContent.Breadcrumb(emptyList())),
                        node("database", ReferenceBlockContent.DatabaseLink(reference, null, "Engineering wiki")),
                        BlockNode(
                            BlockId("template"),
                            ReusableBlockContent.Template(listOf(InlineContent.Text("Weekly review"))),
                            children = listOf(node("template-child", TextBlockContent.Paragraph(listOf(InlineContent.Text("Prompt"))))),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).contains(
            "aria-label=\"Breadcrumb\"",
            "href=\"/\"",
            ">Home</a>",
            "aria-current=\"page\">Post</span>",
            "Engineering wiki",
            "Weekly review",
            "Prompt",
        )
    }

    @Test
    fun `renders synchronized children without an extra placeholder and keeps empty fallbacks visible`() {
        val page =
            page(
                nodes =
                    listOf(
                        BlockNode(
                            BlockId("synced-content"),
                            ReusableBlockContent.Synchronized(null),
                            children = listOf(node("synced-child", TextBlockContent.Paragraph(listOf(InlineContent.Text("Synced body"))))),
                        ),
                        node("synced-empty", ReusableBlockContent.Synchronized(null)),
                        node(
                            "synced-reference-empty",
                            ReusableBlockContent.Synchronized(
                                SynchronizedBlockOrigin(
                                    SourceDocumentRef(SourceId("notion"), "origin-document"),
                                    "origin-block",
                                ),
                            ),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).contains("Synced body")
        assertThat(Regex("Synchronized content").findAll(html).count()).isEqualTo(2)
    }

    @Test
    fun `renders a human readable meeting notes status`() {
        val page =
            page(
                nodes =
                    listOf(
                        node(
                            "meeting",
                            SpecialBlockContent.MeetingNotes(
                                "Rendering review",
                                MeetingNotesStatus.NOT_STARTED,
                                emptyList(),
                                null,
                            ),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).contains(">Not started</p>")
        assertThat(html).doesNotContain(">NOT_STARTED</p>")
    }
}
