package xyz.robinjoon.notionblog.support.fixture

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostPublication
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.net.URI
import java.time.Instant
import java.util.UUID

// 글 모델용 작은 빌더다. ID를 짧은 라벨에서 만들어 기대값을 읽기 쉽게 유지한다.
object PostFixtures {
    val sourceId = SourceId("notion")
    val capturedAt: Instant = Instant.parse("2026-10-16T00:00:00Z")

    fun postId(label: Int): PostId = PostId(uuid(label))

    fun revisionId(label: Int): PostRevisionId = PostRevisionId(uuid(1_000 + label))

    fun document(externalId: String): SourceDocumentRef = SourceDocumentRef(sourceId, externalId)

    fun paragraphs(vararg texts: String): BlockTree =
        BlockTree(
            texts.mapIndexed { index, text ->
                BlockNode(BlockId("p-$index"), TextBlockContent.Paragraph(listOf(InlineContent.Text(text))))
            },
        )

    fun image(
        id: String,
        source: MediaSource,
    ): BlockNode = BlockNode(BlockId(id), MediaBlockContent.Media(MediaType.IMAGE, source, null, emptyList()))

    fun hosted(
        path: String,
        signature: String = "sig",
        expiresAt: Instant? = null,
    ): MediaSource.SourceHosted = MediaSource.SourceHosted(URI("https://files.example.com/$path?X-Amz-Signature=$signature"), expiresAt)

    fun properties(
        title: String = "Title",
        cover: MediaSource? = null,
    ): PostProperties = PostProperties(title, "Summary", listOf("tag"), cover)

    fun revision(
        postId: PostId,
        revisionId: PostRevisionId,
        content: BlockTree = paragraphs("Body"),
        sourceRevision: String = "rev-1",
    ): PostRevision = PostRevision(revisionId, postId, properties(), content, SourceRevision(sourceRevision), capturedAt)

    fun privatePost(
        postId: PostId,
        latest: PostRevisionId? = null,
        generation: Long = 0,
    ): Post =
        Post.restore(
            postId,
            document("doc-${postId.value}"),
            latest,
            PostPublication(PostVisibility.PRIVATE, null, false, generation, null),
        )

    fun publicPost(
        postId: PostId,
        published: PostRevisionId,
        latest: PostRevisionId = published,
        generation: Long = 1,
    ): Post =
        Post.restore(
            postId,
            document("doc-${postId.value}"),
            latest,
            PostPublication(PostVisibility.PUBLIC, published, false, generation, capturedAt),
        )

    private fun uuid(label: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(label))
}
