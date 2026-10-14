package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostAvailability
import xyz.robinjoon.notionblog.blog.domain.post.PostAvailabilityStatus
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExposedPostFeedRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedPostRepository(JsonBlockTreeSnapshotCodec())
    private val observedAt = Instant.parse("2026-10-05T01:02:03Z")

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `selects only published snapshotted members of the pointed active revision`() {
        val root = postId(1)
        val visible = postId(2)
        val unpublished = postId(3)
        val missingSnapshot = postId(4)
        val neverPublished = postId(5)
        val outsideScope = postId(6)
        val stagingOnly = postId(7)
        val supersededOnly = postId(8)
        val missingAvailability = postId(9)
        val publicationId = PublicationId(UUID.randomUUID())
        val activeRevisionId = UUID.randomUUID()
        database.inTransaction {
            insertPost(root, status = PostAvailabilityStatus.UNPUBLISHED, snapshot = false, firstPublishedAt = null)
            insertPost(visible)
            insertPost(unpublished, status = PostAvailabilityStatus.UNPUBLISHED)
            insertPost(missingSnapshot, snapshot = false)
            insertPost(neverPublished, firstPublishedAt = null)
            insertPost(outsideScope)
            insertPost(stagingOnly)
            insertPost(supersededOnly)
            insertPost(missingAvailability, status = null)
            createPublication(publicationId, activeRevisionId, root)
            listOf(visible, unpublished, missingSnapshot, neverPublished, missingAvailability).forEach {
                insertMember(activeRevisionId, it, root)
            }
            val stagingRevisionId = UUID.randomUUID()
            insertRevision(publicationId, stagingRevisionId, "STAGING")
            insertMember(stagingRevisionId, root)
            insertMember(stagingRevisionId, stagingOnly, root)
            val oldRevisionId = UUID.randomUUID()
            insertRevision(publicationId, oldRevisionId, "SUPERSEDED")
            insertMember(oldRevisionId, root)
            insertMember(oldRevisionId, supersededOnly, root)
            corruptSnapshot(unpublished)
            corruptSnapshot(outsideScope)
        }

        database.inTransaction {
            val entries = repository.findRecentPublishedPosts(publicationId, emptySet(), 20)
            assertThat(entries.map { it.post.id }).containsExactly(visible)
            assertThat(entries.single().post.title).isEqualTo("Post ${visible.value}")
            assertThat(entries.single().firstPublishedAt).isEqualTo(observedAt)
        }
    }

    @Test
    fun `excludes root header footer and missing snapshots before applying the twenty item limit`() {
        val root = postId(100)
        val header = postId(101)
        val footer = postId(102)
        val missing = postId(103)
        val publicationId = PublicationId(UUID.randomUUID())
        val revisionId = UUID.randomUUID()
        val expected = (1..20).map(::postId)
        val tooOld = postId(21)
        database.inTransaction {
            listOf(root, header, footer).forEach { insertPost(it, firstPublishedAt = observedAt.plusSeconds(30)) }
            insertPost(missing, snapshot = false, firstPublishedAt = observedAt.plusSeconds(20))
            expected.reversed().forEach { insertPost(it) }
            insertPost(tooOld, firstPublishedAt = observedAt.minusSeconds(1))
            createPublication(publicationId, revisionId, root)
            (listOf(header, footer, missing) + expected + tooOld).forEach { insertMember(revisionId, it, root) }
            corruptSnapshot(root)
            corruptSnapshot(header)
            corruptSnapshot(footer)
            corruptSnapshot(tooOld)
        }

        database.inTransaction {
            val entries = repository.findRecentPublishedPosts(publicationId, setOf(root, header, footer), 20)
            assertThat(entries.map { it.post.id }).containsExactlyElementsOf(expected)
        }
    }

    @Test
    fun `orders newer first publication ahead of smaller IDs and honors smaller limits`() {
        val root = postId(1)
        val old = postId(2)
        val recent = postId(3)
        val publicationId = PublicationId(UUID.randomUUID())
        val revisionId = UUID.randomUUID()
        database.inTransaction {
            insertPost(root)
            insertPost(old)
            insertPost(recent, firstPublishedAt = observedAt.plusSeconds(1))
            createPublication(publicationId, revisionId, root)
            insertMember(revisionId, old, root)
            insertMember(revisionId, recent, root)
        }

        database.inTransaction {
            assertThat(repository.findRecentPublishedPosts(publicationId, setOf(root), 1).map { it.post.id })
                .containsExactly(recent)
        }
    }

    @Test
    fun `raises a snapshot error if a selected feed entry cannot be decoded`() {
        val root = postId(1)
        val selected = postId(2)
        val publicationId = PublicationId(UUID.randomUUID())
        val revisionId = UUID.randomUUID()
        database.inTransaction {
            insertPost(root)
            insertPost(selected)
            createPublication(publicationId, revisionId, root)
            insertMember(revisionId, selected, root)
            corruptSnapshot(selected)
        }

        database.inTransaction {
            assertThatThrownBy { repository.findRecentPublishedPosts(publicationId, setOf(root), 20) }
                .isInstanceOf(SnapshotContentException::class.java)
        }
    }

    @Test
    fun `does not use an active revision unless the publication pointer selects it`() {
        val root = postId(1)
        val publicationId = PublicationId(UUID.randomUUID())
        val activeRevisionId = UUID.randomUUID()
        database.inTransaction {
            insertPost(root)
            createPublication(publicationId, activeRevisionId, root)
            val stagingRevisionId = UUID.randomUUID()
            insertRevision(publicationId, stagingRevisionId, "STAGING")
            insertMember(stagingRevisionId, root)
            PublicationTable.update({ PublicationTable.publicationId eq publicationId.value }) {
                it[PublicationTable.activeRevisionId] = stagingRevisionId
            }
        }

        database.inTransaction {
            assertThat(repository.findRecentPublishedPosts(publicationId, emptySet(), 20)).isEmpty()
            assertThat(repository.findRecentPublishedPosts(PublicationId(UUID.randomUUID()), emptySet(), 20)).isEmpty()
        }
    }

    @Test
    fun `does not read an active revision owned by another publication`() {
        val root = postId(1)
        val otherRoot = postId(2)
        val publicationId = PublicationId(UUID.randomUUID())
        val otherPublicationId = PublicationId(UUID.randomUUID())
        val otherRevisionId = UUID.randomUUID()
        database.inTransaction {
            insertPost(root)
            insertPost(otherRoot)
            createPublication(publicationId, UUID.randomUUID(), root)
            createPublication(otherPublicationId, otherRevisionId, otherRoot)
        }

        database.inTransaction {
            PublicationTable.update({ PublicationTable.publicationId eq publicationId.value }) {
                it[activeRevisionId] = otherRevisionId
            }
            assertThat(repository.findRecentPublishedPosts(publicationId, emptySet(), 20)).isEmpty()
        }
    }

    @Test
    fun `preserves first publication across later identity snapshot and availability updates`() {
        val postId = postId(1)
        database.inTransaction { insertPost(postId) }
        database.inTransaction {
            repository.saveIdentity(binding(postId), "Changed title", observedAt.plusSeconds(10))
            repository.saveSnapshot(
                Post.restore(postId, "Changed title", BlockTree(emptyList())),
                SourceRevision("new"),
                observedAt.plusSeconds(10),
            )
            repository.saveAvailability(PostAvailability(postId, PostAvailabilityStatus.UNPUBLISHED, observedAt.plusSeconds(20)))
            repository.recordFirstPublication(postId, observedAt.minusSeconds(10))
            repository.saveAvailability(PostAvailability(postId, PostAvailabilityStatus.PUBLISHED, observedAt.plusSeconds(30)))
            repository.recordFirstPublication(postId, observedAt.plusSeconds(30))
        }

        assertThat(firstPublication(postId)).isEqualTo(observedAt)
    }

    private fun firstPublication(postId: PostId): Instant? =
        database.inTransaction {
            PostTable
                .selectAll()
                .where { PostTable.postId eq postId.value }
                .single()[PostTable.firstPublishedAt]
                ?.toInstant()
        }

    private fun insertPost(
        postId: PostId,
        status: PostAvailabilityStatus? = PostAvailabilityStatus.PUBLISHED,
        snapshot: Boolean = true,
        firstPublishedAt: Instant? = observedAt,
    ) {
        val title = "Post ${postId.value}"
        repository.saveIdentity(binding(postId), title, observedAt)
        if (snapshot) repository.saveSnapshot(Post.restore(postId, title, BlockTree(emptyList())), SourceRevision("revision"), observedAt)
        if (status != null) repository.saveAvailability(PostAvailability(postId, status, observedAt))
        if (firstPublishedAt != null) repository.recordFirstPublication(postId, firstPublishedAt)
    }

    private fun createPublication(
        publicationId: PublicationId,
        revisionId: UUID,
        root: PostId,
    ) {
        PublicationTable.insert { it[PublicationTable.publicationId] = publicationId.value }
        insertRevision(publicationId, revisionId, "ACTIVE")
        insertMember(revisionId, root)
        PublicationTable.update({ PublicationTable.publicationId eq publicationId.value }) {
            it[rootPostId] = root.value
            it[activeRevisionId] = revisionId
        }
    }

    private fun insertRevision(
        publicationId: PublicationId,
        revisionId: UUID,
        state: String,
    ) {
        PublicationRevisionTable.insert {
            it[PublicationRevisionTable.revisionId] = revisionId
            it[PublicationRevisionTable.publicationId] = publicationId.value
            it[PublicationRevisionTable.state] = state
            it[startedAt] = observedAt.atOffset(ZoneOffset.UTC)
            it[activatedAt] = if (state == "ACTIVE" || state == "SUPERSEDED") observedAt.atOffset(ZoneOffset.UTC) else null
        }
    }

    private fun insertMember(
        revisionId: UUID,
        postId: PostId,
        parentPostId: PostId? = null,
    ) {
        PublicationMemberTable.insert {
            it[PublicationMemberTable.revisionId] = revisionId
            it[PublicationMemberTable.postId] = postId.value
            it[PublicationMemberTable.parentPostId] = parentPostId?.value
            it[depth] = if (parentPostId == null) 0 else 1
        }
    }

    private fun corruptSnapshot(postId: PostId) {
        PostSnapshotTable.update({ PostSnapshotTable.postId eq postId.value }) {
            it[snapshotJson] = "{\"schemaVersion\":999,\"kind\":\"block_tree_snapshot\",\"blocks\":[]}"
        }
    }

    private fun binding(postId: PostId) = PostSourceBinding(postId, SourceDocumentRef(SourceId("notion-main"), postId.value.toString()))

    private fun postId(number: Int) = PostId(UUID(0, number.toLong()))
}
