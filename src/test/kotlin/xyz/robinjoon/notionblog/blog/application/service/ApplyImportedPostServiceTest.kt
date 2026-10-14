package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.ImportedPost
import xyz.robinjoon.notionblog.blog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ApplyImportedPostServiceTest {
    private val snapshotFailures = mockk<SnapshotFailureReporter>(relaxed = true)
    private val posts = mockk<PostRepository>()
    private val syncStates = mockk<SyncStateRepository>()
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val refreshPolicy =
        RefreshPolicy(
            successInterval = Duration.ofMinutes(15),
            initialFailureDelay = Duration.ofMinutes(2),
            maximumFailureDelay = Duration.ofMinutes(30),
        )

    @Test
    fun `published import of an unknown source document returns the newly created post id`() {
        val postId = postId("00000000-0000-0000-0000-000000000001")
        val imported = importedPost()
        val binding = PostSourceBinding(postId, imported.sourceDocument)
        every { posts.findBinding(imported.sourceDocument) } returns null
        givenIdentityWrite(binding, imported)
        every { posts.find(postId) } returns null
        givenPublishedWrites(postId)
        givenSyncSuccess(postId)

        val result = service { postId }.apply(imported)

        assertThat(result).isEqualTo(postId)
    }

    @Test
    fun `corrupt stored snapshot does not fail a published import`() {
        val postId = postId("00000000-0000-0000-0000-000000000003")
        val imported = importedPost()
        val binding = PostSourceBinding(postId, imported.sourceDocument)
        val failure = SnapshotContentException("unsupported snapshot", IllegalArgumentException("invalid content"))
        every { posts.findBinding(imported.sourceDocument) } returns binding
        givenIdentityWrite(binding, imported)
        every { posts.find(postId) } throws failure
        givenPublishedWrites(postId)
        givenSyncSuccess(postId)

        val result = service { postId }.apply(imported)

        assertThat(result).isEqualTo(postId)
    }

    @Test
    fun `snapshot write failure is propagated rather than treated as corrupt stored content`() {
        val postId = postId("00000000-0000-0000-0000-000000000006")
        val imported = importedPost()
        val binding = PostSourceBinding(postId, imported.sourceDocument)
        val failure = SnapshotContentException("database write failure")
        every { posts.findBinding(imported.sourceDocument) } returns binding
        givenIdentityWrite(binding, imported)
        every { posts.find(postId) } returns null
        every { posts.saveSnapshot(any(), any(), any()) } throws failure

        assertThatThrownBy {
            service { postId }.apply(imported)
        }.isSameAs(failure)
    }

    private fun givenIdentityWrite(
        binding: PostSourceBinding,
        imported: ImportedPost,
    ) {
        every { posts.saveIdentity(binding, imported.title, now) } just Runs
    }

    private fun givenPublishedWrites(postId: PostId) {
        every { posts.saveSnapshot(any(), any(), any()) } just Runs
        every { posts.recordFirstPublication(postId, now) } just Runs
        every { posts.saveAvailability(any()) } just Runs
    }

    private fun givenSyncSuccess(postId: PostId) {
        every { syncStates.find(SyncTarget.Post(postId)) } returns null
        every { syncStates.save(any()) } just Runs
    }

    private fun service(postIdFactory: (SourceDocumentRef) -> PostId) =
        ApplyImportedPostService(posts, syncStates, clock, refreshPolicy, snapshotFailures, postIdFactory)

    private fun importedPost(status: ImportedPublicationStatus = ImportedPublicationStatus.PUBLISHED) =
        ImportedPost(
            sourceDocument = SourceDocumentRef(SourceId("notion-main"), "page-1"),
            title = "Imported title",
            publicationStatus = status,
            sourceRevision = SourceRevision("revision-1"),
            content = BlockTree(emptyList()),
            containedChildren = emptyList(),
        )

    private fun postId(value: String) = PostId(UUID.fromString(value))
}
