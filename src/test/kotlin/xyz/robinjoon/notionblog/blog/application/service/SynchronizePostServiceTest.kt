package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.ImportedPost
import xyz.robinjoon.notionblog.blog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.blog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import java.util.UUID

class SynchronizePostServiceTest {
    private val queries = mockk<SynchronizationQueryService>()
    private val source = mockk<PostSource>()
    private val applyService = mockk<ApplyImportedPostService>()
    private val publicationService = mockk<SynchronizePublicationService>()
    private val cancelService = mockk<CancelInactivePostSynchronizationService>()
    private val service = SynchronizePostService(queries, source, applyService, publicationService, cancelService)

    @Test
    fun `propagates source failures and invalid imports`() {
        val postId = postId()
        val expected = sourceDocument("expected")
        every { queries.loadPost(postId) } returns
            PostSynchronizationContext(
                PublicationId(UUID.randomUUID()),
                postId,
                expected,
                emptySet(),
            )
        val sourceFailure = SourceAccessException()
        every { source.fetch(expected) } throws sourceFailure
        every { applyService.recordFailure(any(), any()) } just runs

        assertThatThrownBy { service.synchronize(postId) }.isSameAs(sourceFailure)

        val mismatched = imported(sourceDocument("unexpected"), emptyList())
        every { source.fetch(expected) } returns mismatched

        assertThatThrownBy { service.synchronize(postId) }.isInstanceOf(IllegalArgumentException::class.java)

        val invalid = imported(expected, emptyList())
        every { source.fetch(expected) } returns invalid
        every { applyService.apply(invalid) } throws IllegalArgumentException()

        assertThatThrownBy { service.synchronize(postId) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `propagates a publication synchronization failure`() {
        val postId = postId()
        val parent = sourceDocument("parent")
        val child = sourceDocument("child")
        every { queries.loadPost(postId) } returns
            PostSynchronizationContext(
                publicationId = PublicationId(UUID.randomUUID()),
                postId = postId,
                sourceDocument = parent,
                activeDirectChildren = emptySet(),
            )
        val imported = imported(parent, listOf(child))
        every { source.fetch(parent) } returns imported
        every { applyService.apply(imported) } returns postId
        every { publicationService.synchronize() } throws SourceAccessException()

        assertThatThrownBy { service.synchronize(postId) }.isInstanceOf(SourceAccessException::class.java)
    }

    private fun imported(
        sourceDocument: SourceDocumentRef,
        children: List<SourceDocumentRef>,
    ) = ImportedPost(
        sourceDocument = sourceDocument,
        title = "Post",
        publicationStatus = ImportedPublicationStatus.PUBLISHED,
        sourceRevision = SourceRevision("revision-1"),
        content = BlockTree(emptyList()),
        containedChildren = children,
    )

    private fun postId() = PostId(UUID.randomUUID())

    private fun sourceDocument(externalId: String) = SourceDocumentRef(SourceId("notion-main"), externalId)
}
