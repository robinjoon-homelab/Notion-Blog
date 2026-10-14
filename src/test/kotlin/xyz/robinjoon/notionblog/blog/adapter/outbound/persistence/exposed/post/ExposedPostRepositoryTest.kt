package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
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
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import java.time.Instant
import java.util.UUID

class ExposedPostRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedPostRepository(JsonBlockTreeSnapshotCodec())

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `round trips identity binding logical snapshot JSON and availability`() {
        val binding = binding("post-1", "external-1")
        val post = post(binding.postId, "A title", "Persisted content")
        val capturedAt = Instant.parse("2026-08-25T01:02:03Z")
        val availability = PostAvailability(binding.postId, PostAvailabilityStatus.PUBLISHED, capturedAt)

        database.inTransaction {
            repository.saveIdentity(binding, post.title, capturedAt)
            repository.saveSnapshot(post, SourceRevision("revision-1"), capturedAt)
            repository.saveAvailability(availability)

            assertThat(repository.find(binding.postId)).isEqualTo(
                xyz.robinjoon.notionblog.blog.application.model
                    .StoredPost(post, SourceRevision("revision-1"), capturedAt, null),
            )
            repository.recordFirstPublication(binding.postId, capturedAt.plusSeconds(5))
            assertThat(repository.find(binding.postId)?.firstPublishedAt).isEqualTo(capturedAt.plusSeconds(5))
            assertThat(repository.findBinding(binding.postId)).isEqualTo(binding)
            assertThat(repository.findBinding(binding.sourceDocument)).isEqualTo(binding)
            assertThat(repository.findAvailability(binding.postId)).isEqualTo(availability)
            val storedSnapshot =
                PostSnapshotTable
                    .selectAll()
                    .where { PostSnapshotTable.postId eq binding.postId.value }
                    .single()[PostSnapshotTable.snapshotJson]
            val storedDocument = JsonMapper.builder().build().readTree(storedSnapshot)
            assertThat(storedDocument.required("kind").stringValue()).isEqualTo("block_tree_snapshot")
        }
    }

    @Test
    fun `retains an existing snapshot when availability becomes unpublished`() {
        val binding = binding("post-1", "external-1")
        val capturedAt = Instant.parse("2026-08-25T01:02:03Z")
        val post = post(binding.postId, "Retained title", "Retained content")

        database.inTransaction {
            repository.saveIdentity(binding, post.title, capturedAt)
            repository.saveSnapshot(post, SourceRevision("revision-1"), capturedAt)
            repository.saveAvailability(
                PostAvailability(binding.postId, PostAvailabilityStatus.UNPUBLISHED, capturedAt.plusSeconds(1)),
            )
        }

        database.inTransaction {
            assertThat(repository.find(binding.postId)?.post).isEqualTo(post)
            assertThat(repository.findAvailability(binding.postId)?.status)
                .isEqualTo(PostAvailabilityStatus.UNPUBLISHED)
        }
    }

    @Test
    fun `updates an existing post identity without changing its source binding`() {
        val binding = binding("post-1", "external-1")
        val initialAt = Instant.parse("2026-08-25T01:02:03Z")
        val updatedAt = initialAt.plusSeconds(1)

        database.inTransaction {
            repository.saveIdentity(binding, "Initial title", initialAt)
            repository.saveIdentity(binding, "Updated title", updatedAt)
        }

        database.inTransaction {
            assertThat(repository.findBinding(binding.postId)).isEqualTo(binding)
            assertThat(
                PostTable
                    .selectAll()
                    .where { PostTable.postId eq binding.postId.value }
                    .single()[PostTable.title],
            ).isEqualTo("Updated title")
        }
    }

    @Test
    fun `does not silently rebind a source document or leave a conflicting candidate post behind`() {
        val original = binding("post-1", "external-1")
        val conflicting = binding("post-2", "external-1")
        val changedAt = Instant.parse("2026-08-25T01:02:03Z")

        database.inTransaction {
            repository.saveIdentity(original, "Original", changedAt)
        }

        assertThatThrownBy {
            database.inTransaction {
                repository.saveIdentity(conflicting, "Conflicting", changedAt)
            }
        }.isInstanceOf(IllegalStateException::class.java)

        database.inTransaction {
            assertThat(repository.findBinding(original.sourceDocument)).isEqualTo(original)
            assertThat(repository.findBinding(conflicting.postId)).isNull()
        }
    }

    @Test
    fun `classifies corrupted logical snapshots instead of treating them as absent`() {
        val binding = binding("post-1", "external-1")
        val capturedAt = Instant.parse("2026-08-25T01:02:03Z")

        database.inTransaction {
            repository.saveIdentity(binding, "Corrupted", capturedAt)
            repository.saveSnapshot(
                post(binding.postId, "Corrupted", "Original"),
                SourceRevision("revision-1"),
                capturedAt,
            )
        }
        database.inTransaction { replaceSnapshotJson(binding.postId, UNSUPPORTED_SNAPSHOT) }

        database.inTransaction {
            assertThatThrownBy { repository.find(binding.postId) }
                .isInstanceOf(SnapshotContentException::class.java)
                .hasCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `returns only decodable snapshots as renderable post ids`() {
        val renderable = binding("post-1", "external-1")
        val corrupted = binding("post-2", "external-2")
        val capturedAt = Instant.parse("2026-08-25T01:02:03Z")

        database.inTransaction {
            listOf(renderable, corrupted).forEach { binding ->
                repository.saveIdentity(binding, binding.sourceDocument.externalId, capturedAt)
                repository.saveSnapshot(
                    post(binding.postId, binding.sourceDocument.externalId, "Content"),
                    SourceRevision("revision-1"),
                    capturedAt,
                )
            }
        }
        database.inTransaction { replaceSnapshotJson(corrupted.postId, UNSUPPORTED_SNAPSHOT) }

        database.inTransaction {
            assertThat(repository.findRenderablePostIds(setOf(renderable.postId, corrupted.postId)))
                .containsExactly(renderable.postId)
        }
    }

    @Test
    fun `loads bindings availabilities and renderable ids for multiple posts`() {
        val first = binding("post-1", "external-1")
        val second = binding("post-2", "external-2")
        val third = binding("post-3", "external-3")
        val capturedAt = Instant.parse("2026-08-25T01:02:03Z")

        database.inTransaction {
            listOf(first, second, third).forEach { binding ->
                repository.saveIdentity(binding, binding.sourceDocument.externalId, capturedAt)
            }
            repository.saveSnapshot(post(first.postId, "First", "First content"), SourceRevision("r1"), capturedAt)
            repository.saveSnapshot(post(second.postId, "Second", "Second content"), SourceRevision("r2"), capturedAt)
            repository.saveAvailabilities(
                listOf(
                    PostAvailability(first.postId, PostAvailabilityStatus.PUBLISHED, capturedAt),
                    PostAvailability(second.postId, PostAvailabilityStatus.UNPUBLISHED, capturedAt),
                ),
            )
        }

        database.inTransaction {
            assertThat(repository.findBindingsBySourceDocuments(setOf(first.sourceDocument, third.sourceDocument)))
                .containsExactlyInAnyOrderEntriesOf(mapOf(first.sourceDocument to first, third.sourceDocument to third))
            assertThat(repository.findBindingsByPostIds(setOf(first.postId, second.postId)))
                .containsExactlyInAnyOrderEntriesOf(mapOf(first.postId to first, second.postId to second))
            assertThat(repository.findAvailabilities(setOf(first.postId, second.postId, third.postId)))
                .containsExactlyInAnyOrderEntriesOf(
                    mapOf(
                        first.postId to PostAvailability(first.postId, PostAvailabilityStatus.PUBLISHED, capturedAt),
                        second.postId to
                            PostAvailability(
                                second.postId,
                                PostAvailabilityStatus.UNPUBLISHED,
                                capturedAt,
                            ),
                    ),
                )
            assertThat(repository.findRenderablePostIds(setOf(first.postId, second.postId, third.postId)))
                .containsExactlyInAnyOrder(first.postId, second.postId)
        }
    }

    private fun binding(
        postToken: String,
        externalId: String,
    ): PostSourceBinding =
        PostSourceBinding(
            PostId(UUID.nameUUIDFromBytes(postToken.toByteArray())),
            SourceDocumentRef(SourceId("notion-main"), externalId),
        )

    private fun post(
        postId: PostId,
        title: String,
        content: String,
    ): Post =
        Post.restore(
            postId,
            title,
            BlockTree(
                listOf(
                    BlockNode(
                        BlockId("paragraph"),
                        TextBlockContent.Paragraph(listOf(InlineContent.Text(content))),
                    ),
                ),
            ),
        )

    // Stores a snapshot JSON document whose schema version the codec does not support.
    private fun replaceSnapshotJson(
        postId: PostId,
        json: String,
    ) {
        PostSnapshotTable.update({ PostSnapshotTable.postId eq postId.value }) { it[snapshotJson] = json }
    }

    private companion object {
        const val UNSUPPORTED_SNAPSHOT = "{\"schemaVersion\":2,\"kind\":\"block_tree_snapshot\",\"blocks\":[]}"
    }
}
