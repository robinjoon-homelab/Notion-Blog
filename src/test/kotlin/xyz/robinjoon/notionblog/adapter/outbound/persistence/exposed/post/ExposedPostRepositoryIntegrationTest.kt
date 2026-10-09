package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedPostRepositoryIntegrationTest {
    private val database = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private val exposedDatabase: Database by lazy {
        Database.connect(
            database.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = database.username,
            password = database.password,
        )
    }
    private val repository = ExposedPostRepository(JsonBlockTreeSnapshotCodec())

    @BeforeAll
    fun migrate() {
        database.start()
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopDatabase() {
        database.stop()
    }

    @BeforeEach
    fun clearTargetTables() {
        connection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "truncate table sync_state, site_configuration, presentation_profile_asset, " +
                        "presentation_profile, " +
                        "publication_member, publication_revision, publication, post_availability, post_snapshot, " +
                        "post_source_binding, post cascade",
                )
            }
        }
    }

    @Test
    fun `round trips identity binding logical JSONB snapshot and availability through V3 tables`() {
        val binding = binding("post-1", "external-1")
        val post = post(binding.postId, "A title", "Persisted content")
        val capturedAt = Instant.parse("2026-08-25T01:02:03Z")
        val availability = PostAvailability(binding.postId, PostAvailabilityStatus.PUBLISHED, capturedAt)

        transaction(exposedDatabase) {
            repository.saveIdentity(binding, post.title, capturedAt)
            repository.saveSnapshot(post, SourceRevision("revision-1"), capturedAt)
            repository.saveAvailability(availability)

            assertThat(repository.find(binding.postId)).isEqualTo(
                xyz.robinjoon.notionblog.application.model
                    .StoredPost(post, SourceRevision("revision-1"), capturedAt),
            )
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

        transaction(exposedDatabase) {
            repository.saveIdentity(binding, post.title, capturedAt)
            repository.saveSnapshot(post, SourceRevision("revision-1"), capturedAt)
            repository.saveAvailability(
                PostAvailability(binding.postId, PostAvailabilityStatus.UNPUBLISHED, capturedAt.plusSeconds(1)),
            )
        }

        transaction(exposedDatabase) {
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

        transaction(exposedDatabase) {
            repository.saveIdentity(binding, "Initial title", initialAt)
            repository.saveIdentity(binding, "Updated title", updatedAt)
        }

        transaction(exposedDatabase) {
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

        transaction(exposedDatabase) {
            repository.saveIdentity(original, "Original", changedAt)
        }

        assertThatThrownBy {
            transaction(exposedDatabase) {
                repository.saveIdentity(conflicting, "Conflicting", changedAt)
            }
        }.isInstanceOf(IllegalStateException::class.java)

        transaction(exposedDatabase) {
            assertThat(repository.findBinding(original.sourceDocument)).isEqualTo(original)
            assertThat(repository.findBinding(conflicting.postId)).isNull()
        }
    }

    @Test
    fun `classifies corrupted logical snapshots instead of treating them as absent`() {
        val binding = binding("post-1", "external-1")
        val capturedAt = Instant.parse("2026-08-25T01:02:03Z")

        transaction(exposedDatabase) {
            repository.saveIdentity(binding, "Corrupted", capturedAt)
            repository.saveSnapshot(
                post(binding.postId, "Corrupted", "Original"),
                SourceRevision("revision-1"),
                capturedAt,
            )
        }
        connection { connection ->
            connection
                .prepareStatement(
                    "update post_snapshot set snapshot_json = ?::jsonb where post_id = ?",
                ).use { statement ->
                    statement.setString(1, "{\"schemaVersion\":2,\"kind\":\"block_tree_snapshot\",\"blocks\":[]}")
                    statement.setObject(2, binding.postId.value)
                    statement.executeUpdate()
                }
        }

        transaction(exposedDatabase) {
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

        transaction(exposedDatabase) {
            listOf(renderable, corrupted).forEach { binding ->
                repository.saveIdentity(binding, binding.sourceDocument.externalId, capturedAt)
                repository.saveSnapshot(
                    post(binding.postId, binding.sourceDocument.externalId, "Content"),
                    SourceRevision("revision-1"),
                    capturedAt,
                )
            }
        }
        connection { connection ->
            connection
                .prepareStatement(
                    "update post_snapshot set snapshot_json = ?::jsonb where post_id = ?",
                ).use { statement ->
                    statement.setString(1, "{\"schemaVersion\":2,\"kind\":\"block_tree_snapshot\",\"blocks\":[]}")
                    statement.setObject(2, corrupted.postId.value)
                    statement.executeUpdate()
                }
        }

        transaction(exposedDatabase) {
            assertThat(repository.findRenderablePostIds(setOf(renderable.postId, corrupted.postId)))
                .containsExactly(renderable.postId)
        }
    }

    @Test
    fun `uses ambient transaction so a failed caller transaction rolls identity changes back`() {
        val binding = binding("post-1", "external-1")

        assertThatThrownBy {
            transaction(exposedDatabase) {
                repository.saveIdentity(binding, "Rolled back", Instant.parse("2026-08-25T01:02:03Z"))
                error("caller failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        transaction(exposedDatabase) {
            assertThat(repository.findBinding(binding.sourceDocument)).isNull()
        }
    }

    @Test
    fun `loads bindings availabilities and renderable ids for multiple posts`() {
        val first = binding("post-1", "external-1")
        val second = binding("post-2", "external-2")
        val third = binding("post-3", "external-3")
        val capturedAt = Instant.parse("2026-08-25T01:02:03Z")

        transaction(exposedDatabase) {
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

        transaction(exposedDatabase) {
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
        Post(
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

    private fun connection(block: (Connection) -> Unit) {
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use(block)
    }
}
