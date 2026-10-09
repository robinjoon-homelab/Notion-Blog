package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.publication

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedPublicationRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostAvailabilityTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationRevisionTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedPublicationRepositoryIntegrationTest {
    private val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private val database: Database by lazy {
        Database.connect(
            url = container.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password,
        )
    }
    private val repository = ExposedPublicationRepository()

    @BeforeAll
    fun migrate() {
        container.start()
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopDatabase() {
        container.stop()
    }

    @BeforeEach
    fun clearDatabase() {
        transaction(database) {
            PublicationTable.update {
                it[rootPostId] = null
                it[activeRevisionId] = null
            }
            PublicationMemberTable.deleteAll()
            PublicationRevisionTable.deleteAll()
            PublicationTable.deleteAll()
            PostAvailabilityTable.deleteAll()
            PostTable.deleteAll()
        }
    }

    @Test
    fun `saves and finds the singleton current publication through its pointer`() =
        inTransaction {
            val publicationId = publicationId()
            val rootPostId = postId()
            val revisionId = revisionId()
            insertPost(rootPostId)

            repository.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))
            repository.createRevision(revision(revisionId, publicationId, PublicationRevisionState.STAGING), startedAt)
            repository.updateRevision(revision(revisionId, publicationId, PublicationRevisionState.ACTIVE), activatedAt)
            repository.save(BlogPublication(publicationId, rootPostId, revisionId))

            assertThat(repository.findCurrent()).isEqualTo(BlogPublication(publicationId, rootPostId, revisionId))
            assertThat(repository.findActiveRevision(publicationId))
                .isEqualTo(revision(revisionId, publicationId, PublicationRevisionState.ACTIVE))
        }

    @Test
    fun `rejects an ambiguous current publication instead of choosing one`() =
        inTransaction {
            repository.save(BlogPublication(publicationId(), rootPostId = null, activeRevisionId = null))
            repository.save(BlogPublication(publicationId(), rootPostId = null, activeRevisionId = null))

            assertThatIllegalStateException()
                .isThrownBy { repository.findCurrent() }
                .withMessage("more than one publication exists")
        }

    @Test
    fun `maps revision lifecycle timestamps and orders staging revisions by start time`() =
        inTransaction {
            val publicationId = publicationId()
            val firstRevisionId = revisionId()
            val secondRevisionId = revisionId()
            repository.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))

            repository.createRevision(revision(firstRevisionId, publicationId, PublicationRevisionState.STAGING), startedAt)
            repository.createRevision(revision(secondRevisionId, publicationId, PublicationRevisionState.STAGING), laterStartedAt)
            repository.updateRevision(revision(firstRevisionId, publicationId, PublicationRevisionState.ACTIVE), activatedAt)
            repository.updateRevision(revision(firstRevisionId, publicationId, PublicationRevisionState.SUPERSEDED), supersededAt)
            repository.updateRevision(revision(secondRevisionId, publicationId, PublicationRevisionState.ABANDONED), abandonedAt)

            val firstRow =
                PublicationRevisionTable
                    .selectAll()
                    .where { PublicationRevisionTable.revisionId eq firstRevisionId.value }
                    .single()
            val secondRow =
                PublicationRevisionTable
                    .selectAll()
                    .where { PublicationRevisionTable.revisionId eq secondRevisionId.value }
                    .single()

            assertThat(firstRow[PublicationRevisionTable.startedAt].toInstant()).isEqualTo(startedAt)
            assertThat(firstRow[PublicationRevisionTable.activatedAt]?.toInstant()).isEqualTo(activatedAt)
            assertThat(secondRow[PublicationRevisionTable.startedAt].toInstant()).isEqualTo(laterStartedAt)
            assertThat(secondRow[PublicationRevisionTable.activatedAt]).isNull()
            assertThat(repository.findStagingRevisions(publicationId)).isEmpty()
        }

    @Test
    fun `creates only staging revisions`() =
        inTransaction {
            val publicationId = publicationId()
            repository.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))

            assertThatIllegalArgumentException()
                .isThrownBy {
                    repository.createRevision(revision(revisionId(), publicationId, PublicationRevisionState.ACTIVE), startedAt)
                }.withMessage("new publication revisions must start in staging")
        }

    @Test
    fun `orders currently staging revisions by their creation transition time`() =
        inTransaction {
            val publicationId = publicationId()
            val firstRevisionId = revisionId()
            val secondRevisionId = revisionId()
            repository.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))
            repository.createRevision(revision(secondRevisionId, publicationId, PublicationRevisionState.STAGING), laterStartedAt)
            repository.createRevision(revision(firstRevisionId, publicationId, PublicationRevisionState.STAGING), startedAt)

            assertThat(repository.findStagingRevisions(publicationId)).containsExactly(
                revision(firstRevisionId, publicationId, PublicationRevisionState.STAGING),
                revision(secondRevisionId, publicationId, PublicationRevisionState.STAGING),
            )
        }

    @Test
    fun `saves a validated member batch without replacing duplicates`() {
        val publicationId = publicationId()
        val revisionId = revisionId()
        val rootPostId = postId()
        val childPostId = postId()
        val otherRevisionId = revisionId()
        val root = PublicationMember(revisionId, rootPostId, parentPostId = null, depth = 0)
        val child = PublicationMember(revisionId, childPostId, parentPostId = rootPostId, depth = 1)

        inTransaction {
            insertPost(rootPostId)
            insertPost(childPostId)
            repository.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))
            repository.createRevision(revision(revisionId, publicationId, PublicationRevisionState.STAGING), startedAt)

            assertThatIllegalArgumentException().isThrownBy {
                repository.saveMembers(revisionId, listOf(root.copy(revisionId = otherRevisionId)))
            }
            repository.saveMembers(revisionId, listOf(root, child))
            assertThat(repository.findMembers(revisionId)).containsExactly(root, child)
        }

        assertThatThrownBy {
            inTransaction { repository.saveMembers(revisionId, listOf(root)) }
        }.hasMessageContaining("publication_member")

        inTransaction {
            assertThat(repository.findMembers(revisionId)).containsExactly(root, child)
        }
    }

    @Test
    fun `finds active members and direct children without applying availability`() =
        inTransaction {
            val publicationId = publicationId()
            val revisionId = revisionId()
            val otherPublicationId = publicationId()
            val otherRevisionId = revisionId()
            val rootPostId = postId()
            val unpublishedChildPostId = postId()
            val grandchildPostId = postId()
            val unrelatedPostId = postId()
            listOf(rootPostId, unpublishedChildPostId, grandchildPostId, unrelatedPostId).forEach(::insertPost)
            insertAvailability(unpublishedChildPostId, "UNPUBLISHED")

            repository.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))
            repository.createRevision(revision(revisionId, publicationId, PublicationRevisionState.STAGING), startedAt)
            repository.saveMembers(
                revisionId,
                listOf(
                    PublicationMember(revisionId, rootPostId, parentPostId = null, depth = 0),
                    PublicationMember(revisionId, unpublishedChildPostId, parentPostId = rootPostId, depth = 1),
                    PublicationMember(revisionId, grandchildPostId, parentPostId = unpublishedChildPostId, depth = 2),
                ),
            )
            repository.updateRevision(revision(revisionId, publicationId, PublicationRevisionState.ACTIVE), activatedAt)
            repository.save(BlogPublication(publicationId, rootPostId, revisionId))

            repository.save(BlogPublication(otherPublicationId, rootPostId = null, activeRevisionId = null))
            repository.createRevision(revision(otherRevisionId, otherPublicationId, PublicationRevisionState.STAGING), startedAt)

            assertThat(repository.findActiveMemberPostIds(publicationId, setOf(unpublishedChildPostId, unrelatedPostId)))
                .containsExactly(unpublishedChildPostId)
            assertThat(repository.findActiveDirectChildren(publicationId, rootPostId)).containsExactly(
                PublicationMember(revisionId, unpublishedChildPostId, parentPostId = rootPostId, depth = 1),
            )
        }

    @Test
    fun `does not expose a publication pointer whose revision is no longer active`() =
        inTransaction {
            val publicationId = publicationId()
            val revisionId = revisionId()
            val rootPostId = postId()
            val childPostId = postId()
            insertPost(rootPostId)
            insertPost(childPostId)
            repository.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))
            repository.createRevision(revision(revisionId, publicationId, PublicationRevisionState.STAGING), startedAt)
            repository.saveMembers(
                revisionId,
                listOf(
                    PublicationMember(revisionId, rootPostId, parentPostId = null, depth = 0),
                    PublicationMember(revisionId, childPostId, parentPostId = rootPostId, depth = 1),
                ),
            )
            repository.updateRevision(revision(revisionId, publicationId, PublicationRevisionState.ACTIVE), activatedAt)
            repository.save(BlogPublication(publicationId, rootPostId, revisionId))
            PublicationRevisionTable.update({ PublicationRevisionTable.revisionId eq revisionId.value }) {
                it[state] = PublicationRevisionState.SUPERSEDED.name
            }

            assertThat(repository.findActiveRevision(publicationId)).isNull()
            assertThat(repository.findActiveMemberPostIds(publicationId, setOf(rootPostId, childPostId))).isEmpty()
            assertThat(repository.findActiveDirectChildren(publicationId, rootPostId)).isEmpty()
        }

    @Test
    fun `participates in the caller transaction rollback without opening a nested transaction`() {
        val publicationId = publicationId()

        assertThatThrownBy {
            inTransaction {
                repository.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))
                error("force application transaction rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        inTransaction {
            assertThat(repository.findCurrent()).isNull()
        }
    }

    private fun inTransaction(block: () -> Unit) = transaction(database) { block() }

    private fun publicationId() = PublicationId(UUID.randomUUID())

    private fun revisionId() = PublicationRevisionId(UUID.randomUUID())

    private fun postId() = PostId(UUID.randomUUID())

    private fun revision(
        id: PublicationRevisionId,
        publicationId: PublicationId,
        state: PublicationRevisionState,
    ) = PublicationRevision(id, publicationId, state)

    private fun insertPost(postId: PostId) {
        PostTable.insert {
            it[PostTable.postId] = postId.value
            it[title] = "Post $postId"
            it[createdAt] = startedAt.atOffset(ZoneOffset.UTC)
            it[updatedAt] = startedAt.atOffset(ZoneOffset.UTC)
        }
    }

    private fun insertAvailability(
        postId: PostId,
        status: String,
    ) {
        PostAvailabilityTable.insert {
            it[PostAvailabilityTable.postId] = postId.value
            it[PostAvailabilityTable.status] = status
            it[confirmedAt] = startedAt.atOffset(ZoneOffset.UTC)
        }
    }

    private companion object {
        val startedAt: Instant = Instant.parse("2026-08-25T00:00:00Z")
        val laterStartedAt: Instant = Instant.parse("2026-08-25T00:01:00Z")
        val activatedAt: Instant = Instant.parse("2026-08-25T00:02:00Z")
        val supersededAt: Instant = Instant.parse("2026-08-25T00:03:00Z")
        val abandonedAt: Instant = Instant.parse("2026-08-25T00:04:00Z")
    }
}
