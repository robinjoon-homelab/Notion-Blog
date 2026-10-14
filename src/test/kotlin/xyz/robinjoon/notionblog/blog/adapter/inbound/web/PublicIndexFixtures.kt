package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.PublicIndex
import xyz.robinjoon.notionblog.blog.application.model.PublicIndexEntry
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import java.time.Instant
import java.util.UUID

internal object PublicIndexFixtures {
    val rootId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val articleId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val metadata = SiteMetadata("My blog", "Notes about building things", "ko-KR", null)

    fun index(
        metadata: SiteMetadata = this.metadata,
        entries: List<PublicIndexEntry> = listOf(root(), article()),
    ) = PublicIndex(metadata, entries)

    fun root(body: String = "Welcome text") =
        PublicIndexEntry(post(rootId, "Home", body), true, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-03T04:05:06.789Z"))

    fun article(
        title: String = "First article",
        body: String = "Article body",
    ) = PublicIndexEntry(post(articleId, title, body), false, Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-04T00:00:00Z"))

    private fun post(
        id: PostId,
        title: String,
        body: String,
    ): Post {
        val blocks =
            if (body.isEmpty()) {
                emptyList()
            } else {
                listOf(BlockNode(BlockId("p"), TextBlockContent.Paragraph(listOf(InlineContent.Text(body)))))
            }
        return Post.restore(id, title, BlockTree(blocks))
    }
}
