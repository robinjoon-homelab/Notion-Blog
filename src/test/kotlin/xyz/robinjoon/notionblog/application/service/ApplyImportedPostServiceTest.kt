package xyz.robinjoon.notionblog.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.application.model.StoredPost
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.source.PostSourceBinding
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

class ApplyImportedPostServiceTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val refreshPolicy = RefreshPolicy(
        successInterval = Duration.ofMinutes(15),
        initialFailureDelay = Duration.ofMinutes(2),
        maximumFailureDelay = Duration.ofMinutes(30),
    )

    @Test
    fun `apply and record failure own transaction boundaries`() {
        assertThat(
            ApplyImportedPostService::class.memberFunctions
                .single { it.name == "apply" }
                .findAnnotation<Transactional>(),
        ).isNotNull()
        assertThat(
            ApplyImportedPostService::class.memberFunctions
                .single { it.name == "recordFailure" }
                .findAnnotation<Transactional>(),
        ).isNotNull()
    }

    @Test
    fun `published import creates a binding then atomically saves identity snapshot availability and success state`() {
        val operations = mutableListOf<String>()
        val postId = postId("00000000-0000-0000-0000-000000000001")
        val posts = RecordingPostRepository(operations)
        val states = RecordingSyncStateRepository(operations)
        val imported = importedPost()

        val result = service(posts, states) { reference ->
            operations += "new-id"
            assertThat(reference).isEqualTo(imported.sourceDocument)
            postId
        }.apply(imported)

        assertThat(result).isEqualTo(postId)
        assertThat(posts.binding).isEqualTo(PostSourceBinding(postId, imported.sourceDocument))
        assertThat(posts.identity).isEqualTo(IdentityWrite(posts.binding!!, imported.title, now))
        assertThat(posts.snapshot).isEqualTo(
            SnapshotWrite(Post(postId, imported.title, imported.content), imported.sourceRevision, now),
        )
        assertThat(posts.firstPublishedAt).isEqualTo(now)
        assertThat(posts.availability).isEqualTo(PostAvailability(postId, PostAvailabilityStatus.PUBLISHED, now))
        assertThat(states.saved).isEqualTo(
            SyncState(SyncTarget.Post(postId), now, now.plus(Duration.ofMinutes(15)), 0, null),
        )
        assertThat(operations).containsExactly(
            "find-binding",
            "new-id",
            "save-identity",
            "find-post",
            "save-snapshot",
            "record-first-publication",
            "save-availability",
            "find-sync",
            "save-sync",
        )
    }

    @Test
    fun `same published revision keeps existing snapshot while reconfirming identity availability and sync success`() {
        val operations = mutableListOf<String>()
        val postId = postId("00000000-0000-0000-0000-000000000002")
        val imported = importedPost()
        val posts = RecordingPostRepository(operations).apply {
            binding = PostSourceBinding(postId, imported.sourceDocument)
            storedPost = StoredPost(
                Post(postId, "Old title", BlockTree(emptyList())),
                imported.sourceRevision,
                now.minusSeconds(30),
            )
        }
        val states = RecordingSyncStateRepository(operations)

        service(posts, states) { error("a known source document must not create a new id") }.apply(imported)

        assertThat(posts.snapshot).isNull()
        assertThat(posts.firstPublishedAt).isEqualTo(now)
        assertThat(posts.identity).isEqualTo(IdentityWrite(posts.binding!!, imported.title, now))
        assertThat(posts.availability).isEqualTo(PostAvailability(postId, PostAvailabilityStatus.PUBLISHED, now))
        assertThat(operations).containsExactly(
            "find-binding",
            "save-identity",
            "find-post",
            "record-first-publication",
            "save-availability",
            "find-sync",
            "save-sync",
        )
    }

    @Test
    fun `changed published revision replaces the snapshot`() {
        val operations = mutableListOf<String>()
        val postId = postId("00000000-0000-0000-0000-000000000007")
        val imported = importedPost()
        val posts = RecordingPostRepository(operations).apply {
            binding = PostSourceBinding(postId, imported.sourceDocument)
            storedPost = StoredPost(
                Post(postId, "Old title", BlockTree(emptyList())),
                SourceRevision("revision-0"),
                now.minusSeconds(30),
            )
        }
        val states = RecordingSyncStateRepository(operations)

        service(posts, states) { error("a known source document must not create a new id") }.apply(imported)

        assertThat(posts.snapshot).isEqualTo(
            SnapshotWrite(Post(postId, imported.title, imported.content), imported.sourceRevision, now),
        )
        assertThat(operations).containsExactly(
            "find-binding",
            "save-identity",
            "find-post",
            "save-snapshot",
            "record-first-publication",
            "save-availability",
            "find-sync",
            "save-sync",
        )
    }

    @Test
    fun `corrupt stored snapshot is replaced when a published import is applied`() {
        val operations = mutableListOf<String>()
        val postId = postId("00000000-0000-0000-0000-000000000003")
        val imported = importedPost()
        val posts = RecordingPostRepository(operations).apply {
            binding = PostSourceBinding(postId, imported.sourceDocument)
            findPostFailure = SnapshotContentException("unsupported snapshot")
        }
        val states = RecordingSyncStateRepository(operations)

        service(posts, states) { error("a known source document must not create a new id") }.apply(imported)

        assertThat(posts.snapshot).isEqualTo(
            SnapshotWrite(Post(postId, imported.title, imported.content), imported.sourceRevision, now),
        )
        assertThat(operations).containsExactly(
            "find-binding",
            "save-identity",
            "find-post",
            "save-snapshot",
            "record-first-publication",
            "save-availability",
            "find-sync",
            "save-sync",
        )
    }

    @Test
    fun `unpublished import saves identity and immediate unavailability without overwriting an existing snapshot`() {
        val operations = mutableListOf<String>()
        val postId = postId("00000000-0000-0000-0000-000000000004")
        val imported = importedPost(status = ImportedPublicationStatus.UNPUBLISHED)
        val posts = RecordingPostRepository(operations).apply {
            binding = PostSourceBinding(postId, imported.sourceDocument)
        }
        val states = RecordingSyncStateRepository(operations)

        service(posts, states) { error("a known source document must not create a new id") }.apply(imported)

        assertThat(posts.snapshot).isNull()
        assertThat(posts.firstPublishedAt).isNull()
        assertThat(posts.availability).isEqualTo(PostAvailability(postId, PostAvailabilityStatus.UNPUBLISHED, now))
        assertThat(operations).containsExactly(
            "find-binding",
            "save-identity",
            "save-availability",
            "find-sync",
            "save-sync",
        )
    }

    @Test
    fun `failure only updates retry state and does not turn a post unpublished`() {
        val operations = mutableListOf<String>()
        val postId = postId("00000000-0000-0000-0000-000000000005")
        val posts = RecordingPostRepository(operations)
        val states = RecordingSyncStateRepository(operations).apply {
            existing = SyncState(
                target = SyncTarget.Post(postId),
                lastSuccessAt = now.minusSeconds(60),
                refreshAfter = now,
                failureCount = 1,
                lastErrorKind = SyncFailureKind.RETRYABLE_SOURCE,
            )
        }

        service(posts, states) { error("not used") }.recordFailure(postId, SyncFailureKind.ACCESS)

        assertThat(posts.availability).isNull()
        assertThat(posts.firstPublishedAt).isNull()
        assertThat(posts.snapshot).isNull()
        assertThat(states.saved).isEqualTo(
            SyncState(
                target = SyncTarget.Post(postId),
                lastSuccessAt = now.minusSeconds(60),
                refreshAfter = now.plus(Duration.ofMinutes(4)),
                failureCount = 2,
                lastErrorKind = SyncFailureKind.ACCESS,
            ),
        )
        assertThat(operations).containsExactly("find-sync", "save-sync")
    }

    @Test
    fun `snapshot write failure is propagated rather than treated as corrupt stored content`() {
        val operations = mutableListOf<String>()
        val postId = postId("00000000-0000-0000-0000-000000000006")
        val imported = importedPost()
        val posts = RecordingPostRepository(operations).apply {
            binding = PostSourceBinding(postId, imported.sourceDocument)
            snapshotWriteFailure = SnapshotContentException("database write failure")
        }
        val states = RecordingSyncStateRepository(operations)

        assertThatThrownBy {
            service(posts, states) { error("a known source document must not create a new id") }.apply(imported)
        }.isInstanceOf(SnapshotContentException::class.java)

        assertThat(posts.availability).isNull()
        assertThat(posts.firstPublishedAt).isNull()
        assertThat(states.saved).isNull()
        assertThat(operations).containsExactly("find-binding", "save-identity", "find-post", "save-snapshot")
    }

    @Test
    fun `first publication time survives unchanged sync edits unpublishing and republication`() {
        val operations = mutableListOf<String>()
        val postId = postId("00000000-0000-0000-0000-000000000008")
        val posts = RecordingPostRepository(operations)
        val states = RecordingSyncStateRepository(operations)
        val imported = importedPost()
        val firstPublishedAt = now.plusSeconds(60)

        fun applyAt(at: Instant, value: ImportedPost) {
            ApplyImportedPostService(posts, states, Clock.fixed(at, ZoneOffset.UTC), refreshPolicy) { postId }.apply(value)
        }

        applyAt(now, imported.copy(publicationStatus = ImportedPublicationStatus.UNPUBLISHED))
        assertThat(posts.firstPublishedAt).isNull()
        assertThat(posts.storedPost).isNull()

        applyAt(firstPublishedAt, imported)
        assertThat(posts.firstPublishedAt).isEqualTo(firstPublishedAt)
        assertThat(posts.storedPost?.capturedAt).isEqualTo(firstPublishedAt)

        applyAt(now.plusSeconds(120), imported)
        assertThat(posts.firstPublishedAt).isEqualTo(firstPublishedAt)
        assertThat(posts.storedPost?.capturedAt).isEqualTo(firstPublishedAt)

        val edited = imported.copy(title = "Edited title", sourceRevision = SourceRevision("revision-2"))
        applyAt(now.plusSeconds(180), edited)
        assertThat(posts.firstPublishedAt).isEqualTo(firstPublishedAt)
        assertThat(posts.storedPost?.post?.title).isEqualTo("Edited title")
        assertThat(posts.storedPost?.capturedAt).isEqualTo(now.plusSeconds(180))

        applyAt(now.plusSeconds(240), edited.copy(publicationStatus = ImportedPublicationStatus.UNPUBLISHED))
        assertThat(posts.firstPublishedAt).isEqualTo(firstPublishedAt)
        assertThat(posts.availability?.status).isEqualTo(PostAvailabilityStatus.UNPUBLISHED)
        assertThat(posts.storedPost?.capturedAt).isEqualTo(now.plusSeconds(180))

        applyAt(now.plusSeconds(300), edited)
        assertThat(posts.firstPublishedAt).isEqualTo(firstPublishedAt)
        assertThat(posts.availability?.status).isEqualTo(PostAvailabilityStatus.PUBLISHED)
        assertThat(posts.storedPost?.capturedAt).isEqualTo(now.plusSeconds(180))
    }

    private fun service(
        posts: PostRepository,
        syncStates: SyncStateRepository,
        postIdFactory: (SourceDocumentRef) -> PostId,
    ) = ApplyImportedPostService(posts, syncStates, clock, refreshPolicy, postIdFactory)

    private fun importedPost(status: ImportedPublicationStatus = ImportedPublicationStatus.PUBLISHED) = ImportedPost(
        sourceDocument = SourceDocumentRef(SourceId("notion-main"), "page-1"),
        title = "Imported title",
        publicationStatus = status,
        sourceRevision = SourceRevision("revision-1"),
        content = BlockTree(emptyList()),
        containedChildren = emptyList(),
    )

    private fun postId(value: String) = PostId(UUID.fromString(value))

    private class RecordingPostRepository(
        private val operations: MutableList<String>,
    ) : PostRepository {
        var binding: PostSourceBinding? = null
        var storedPost: StoredPost? = null
        var findPostFailure: RuntimeException? = null
        var snapshotWriteFailure: RuntimeException? = null
        var identity: IdentityWrite? = null
        var snapshot: SnapshotWrite? = null
        var firstPublishedAt: Instant? = null
        var availability: PostAvailability? = null

        override fun find(postId: PostId): StoredPost? {
            operations += "find-post"
            findPostFailure?.let { throw it }
            return storedPost
        }

        override fun findBinding(postId: PostId): PostSourceBinding? = binding

        override fun findBinding(sourceDocument: SourceDocumentRef): PostSourceBinding? {
            operations += "find-binding"
            return binding
        }

        override fun findBindingsBySourceDocuments(
            sourceDocuments: Set<SourceDocumentRef>,
        ): Map<SourceDocumentRef, PostSourceBinding> = emptyMap()

        override fun findBindingsByPostIds(postIds: Set<PostId>): Map<PostId, PostSourceBinding> = emptyMap()

        override fun findRecentPublishedPosts(
            publicationId: PublicationId,
            excludedPostIds: Set<PostId>,
            limit: Int,
        ): List<PostFeedEntry> = error("feed lookup is not used while applying imports")

        override fun saveIdentity(binding: PostSourceBinding, title: String, changedAt: Instant) {
            operations += "save-identity"
            this.binding = binding
            identity = IdentityWrite(binding, title, changedAt)
        }

        override fun saveSnapshot(post: Post, sourceRevision: SourceRevision, capturedAt: Instant) {
            operations += "save-snapshot"
            snapshotWriteFailure?.let { throw it }
            snapshot = SnapshotWrite(post, sourceRevision, capturedAt)
            storedPost = StoredPost(post, sourceRevision, capturedAt)
        }

        override fun recordFirstPublication(postId: PostId, observedAt: Instant) {
            operations += "record-first-publication"
            check(storedPost?.post?.id == postId) { "a published snapshot must exist before recording the first publication" }
            if (firstPublishedAt == null) {
                firstPublishedAt = observedAt
            }
        }

        override fun findAvailability(postId: PostId): PostAvailability? = availability

        override fun findAvailabilities(postIds: Set<PostId>): Map<PostId, PostAvailability> = emptyMap()

        override fun saveAvailability(availability: PostAvailability) {
            operations += "save-availability"
            this.availability = availability
        }

        override fun saveAvailabilities(availabilities: Collection<PostAvailability>) = Unit

        override fun findRenderablePostIds(postIds: Set<PostId>): Set<PostId> = emptySet()
    }

    private class RecordingSyncStateRepository(
        private val operations: MutableList<String>,
    ) : SyncStateRepository {
        var existing: SyncState? = null
        var saved: SyncState? = null

        override fun delete(target: SyncTarget) {
            existing = existing?.takeUnless { it.target == target }
            saved = saved?.takeUnless { it.target == target }
        }

        override fun findDue(now: Instant, limit: Int): List<SyncState> = emptyList()

        override fun find(target: SyncTarget): SyncState? {
            operations += "find-sync"
            return existing
        }

        override fun save(state: SyncState) {
            operations += "save-sync"
            saved = state
        }
    }

    private data class IdentityWrite(
        val binding: PostSourceBinding,
        val title: String,
        val changedAt: Instant,
    )

    private data class SnapshotWrite(
        val post: Post,
        val sourceRevision: SourceRevision,
        val capturedAt: Instant,
    )
}
