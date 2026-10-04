package xyz.robinjoon.notionblog.application.service

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

class SynchronizePostServiceTest {
    private val queries = mockk<SynchronizationQueryService>()
    private val source = mockk<PostSource>()
    private val applyService = mockk<ApplyImportedPostService>()
    private val publicationService = mockk<SynchronizePublicationService>()
    private val cancelService = mockk<CancelInactivePostSynchronizationService>()
    private val service = SynchronizePostService(queries, source, applyService, publicationService, cancelService)

    @Test
    fun `checks cancellation without fetching when the post context is unavailable`() {
        val postId = postId()
        every { queries.loadPost(postId) } returns null
        every { cancelService.cancelIfInactive(postId) } just runs

        service.synchronize(postId)

        verify(exactly = 1) { cancelService.cancelIfInactive(postId) }
        verify(exactly = 0) { source.fetch(any()) }
        verify(exactly = 0) { applyService.apply(any()) }
        verify(exactly = 0) { publicationService.synchronize() }
    }

    @Test
    fun `fetches outside a transaction and synchronizes only when structural child sets differ`() {
        val postId = postId()
        val parent = sourceDocument("parent")
        val child = sourceDocument("child")
        val second = sourceDocument("second")
        every { queries.loadPost(postId) } returns PostSynchronizationContext(
            publicationId = PublicationId(UUID.randomUUID()),
            postId = postId,
            sourceDocument = parent,
            activeDirectChildren = setOf(child, second),
        )
        val reorderedChildren = listOf(second, child)
        val imported = imported(parent, reorderedChildren)
        every { source.fetch(parent) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            imported
        }
        every { applyService.apply(imported) } returns postId
        every { publicationService.synchronize() } just runs

        service.synchronize(postId)

        verify(exactly = 0) { publicationService.synchronize() }

        val changedChildren = imported(parent, listOf(child))
        every { source.fetch(parent) } returns changedChildren
        every { applyService.apply(changedChildren) } returns postId
        service.synchronize(postId)

        verify(exactly = 1) { publicationService.synchronize() }
    }

    @Test
    fun `preserves old post state by skipping apply for source failures and invalid imports`() {
        val postId = postId()
        val expected = sourceDocument("expected")
        every { queries.loadPost(postId) } returns PostSynchronizationContext(
            PublicationId(UUID.randomUUID()),
            postId,
            expected,
            emptySet(),
        )
        val sourceFailure = SourceAccessException()
        every { source.fetch(expected) } throws sourceFailure
        every { applyService.recordFailure(any(), any()) } just runs

        assertThatThrownBy { service.synchronize(postId) }.isSameAs(sourceFailure)
        verify(exactly = 1) { applyService.recordFailure(postId, SyncFailureKind.ACCESS) }
        verify(exactly = 0) { applyService.apply(any()) }

        val mismatched = imported(sourceDocument("unexpected"), emptyList())
        every { source.fetch(expected) } returns mismatched

        assertThatThrownBy { service.synchronize(postId) }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 1) { applyService.recordFailure(postId, SyncFailureKind.MAPPING) }
        verify(exactly = 0) { applyService.apply(any()) }

        val invalid = imported(expected, emptyList())
        every { source.fetch(expected) } returns invalid
        every { applyService.apply(invalid) } throws IllegalArgumentException()

        assertThatThrownBy { service.synchronize(postId) }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 2) { applyService.recordFailure(postId, SyncFailureKind.MAPPING) }
    }

    @Test
    fun `does not record a post failure after publication synchronization fails`() {
        val postId = postId()
        val parent = sourceDocument("parent")
        val child = sourceDocument("child")
        every { queries.loadPost(postId) } returns PostSynchronizationContext(
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

        verify(exactly = 0) { applyService.recordFailure(any(), any()) }
    }

    @Test
    fun `does not declare a transaction boundary`() {
        assertThat(SynchronizePostService::class.findAnnotation<Transactional>()).isNull()
        assertThat(SynchronizePostService::class.memberFunctions)
            .noneMatch { it.findAnnotation<Transactional>() != null }
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
