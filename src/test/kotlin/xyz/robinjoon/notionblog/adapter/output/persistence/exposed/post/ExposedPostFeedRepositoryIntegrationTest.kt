package xyz.robinjoon.notionblog.adapter.output.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
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
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.table.PublicationRevisionTable
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.adapter.output.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedPostFeedRepositoryIntegrationTest {
    private val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private lateinit var database: Database
    private val repository = ExposedPostRepository(JsonBlockTreeSnapshotCodec())
    private val observedAt = Instant.parse("2026-10-05T01:02:03Z")

    @BeforeAll
    fun migrate() {
        container.start()
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        database = Database.connect(
            container.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password,
        )
    }

    @AfterAll
    fun stopDatabase() {
        container.stop()
    }

    @BeforeEach
    fun clearDatabase() {
        connection { connection ->
            connection.createStatement().use {
                it.execute("truncate table publication_member, publication_revision, publication, post cascade")
            }
        }
    }

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
        transaction(database) {
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

        transaction(database) {
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
        transaction(database) {
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

        transaction(database) {
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
        transaction(database) {
            insertPost(root)
            insertPost(old)
            insertPost(recent, firstPublishedAt = observedAt.plusSeconds(1))
            createPublication(publicationId, revisionId, root)
            insertMember(revisionId, old, root)
            insertMember(revisionId, recent, root)
        }

        transaction(database) {
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
        transaction(database) {
            insertPost(root)
            insertPost(selected)
            createPublication(publicationId, revisionId, root)
            insertMember(revisionId, selected, root)
            corruptSnapshot(selected)
        }

        transaction(database) {
            assertThatThrownBy { repository.findRecentPublishedPosts(publicationId, setOf(root), 20) }
                .isInstanceOf(SnapshotContentException::class.java)
        }
    }

    @Test
    fun `does not use an active revision unless the publication pointer selects it`() {
        val root = postId(1)
        val publicationId = PublicationId(UUID.randomUUID())
        val activeRevisionId = UUID.randomUUID()
        transaction(database) {
            insertPost(root)
            createPublication(publicationId, activeRevisionId, root)
            val stagingRevisionId = UUID.randomUUID()
            insertRevision(publicationId, stagingRevisionId, "STAGING")
            insertMember(stagingRevisionId, root)
            PublicationTable.update({ PublicationTable.publicationId eq publicationId.value }) {
                it[PublicationTable.activeRevisionId] = stagingRevisionId
            }
        }

        transaction(database) {
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
        transaction(database) {
            insertPost(root)
            insertPost(otherRoot)
            createPublication(publicationId, UUID.randomUUID(), root)
            createPublication(otherPublicationId, otherRevisionId, otherRoot)
        }

        transaction(database) {
            exec("set constraints publication_active_revision_ownership_fkey deferred")
            PublicationTable.update({ PublicationTable.publicationId eq publicationId.value }) {
                it[activeRevisionId] = otherRevisionId
            }
            assertThat(repository.findRecentPublishedPosts(publicationId, emptySet(), 20)).isEmpty()
            rollback()
        }
    }

    @Test
    fun `preserves first publication across later identity snapshot and availability updates`() {
        val postId = postId(1)
        transaction(database) { insertPost(postId) }
        transaction(database) {
            repository.saveIdentity(binding(postId), "Changed title", observedAt.plusSeconds(10))
            repository.saveSnapshot(Post(postId, "Changed title", BlockTree(emptyList())), SourceRevision("new"), observedAt.plusSeconds(10))
            repository.saveAvailability(PostAvailability(postId, PostAvailabilityStatus.UNPUBLISHED, observedAt.plusSeconds(20)))
            repository.recordFirstPublication(postId, observedAt.minusSeconds(10))
            repository.saveAvailability(PostAvailability(postId, PostAvailabilityStatus.PUBLISHED, observedAt.plusSeconds(30)))
            repository.recordFirstPublication(postId, observedAt.plusSeconds(30))
        }

        assertThat(firstPublication(postId)).isEqualTo(observedAt)
    }

    @Test
    fun `concurrent writers preserve the publication timestamp of the committed first writer`() {
        assertConcurrentPublication(rollbackFirst = false)
    }

    @Test
    fun `concurrent second writer records its timestamp when the first writer rolls back`() {
        assertConcurrentPublication(rollbackFirst = true)
    }

    private fun assertConcurrentPublication(rollbackFirst: Boolean) {
        val postId = postId(1)
        transaction(database) { insertPost(postId, firstPublishedAt = null) }
        val written = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstConnection = CompletableFuture<Int>()
        val secondConnection = CompletableFuture<Int>()
        val secondAt = observedAt.minusSeconds(60)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstWriter = executor.submit {
                transaction(database) {
                    maxAttempts = 1
                    exec("set local lock_timeout = '10s'")
                    firstConnection.complete(
                        requireNotNull(
                            exec("select pg_backend_pid()") { rows ->
                                rows.next()
                                rows.getInt(1)
                            },
                        ),
                    )
                    repository.recordFirstPublication(postId, observedAt)
                    written.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) { "first writer was not released" }
                    if (rollbackFirst) rollback()
                }
            }
            check(written.await(10, TimeUnit.SECONDS)) { "first writer did not acquire row lock" }
            val secondWriter = executor.submit {
                transaction(database) {
                    maxAttempts = 1
                    exec("set local lock_timeout = '10s'")
                    secondConnection.complete(
                        requireNotNull(
                            exec("select pg_backend_pid()") { rows ->
                                rows.next()
                                rows.getInt(1)
                            },
                        ),
                    )
                    repository.recordFirstPublication(postId, secondAt)
                }
            }
            val firstPid = firstConnection.get(10, TimeUnit.SECONDS)
            val secondPid = secondConnection.get(10, TimeUnit.SECONDS)
            assertThat(secondPid).isNotEqualTo(firstPid)
            awaitBlockedBy(secondPid, firstPid)
            releaseFirst.countDown()
            firstWriter.get(10, TimeUnit.SECONDS)
            secondWriter.get(10, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "publication writers did not terminate" }
        }

        assertThat(firstPublication(postId)).isEqualTo(if (rollbackFirst) secondAt else observedAt)
    }

    private fun awaitBlockedBy(waitingPid: Int, blockingPid: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        connection { connection ->
            connection.prepareStatement("select ? = any(pg_blocking_pids(?))").use { statement ->
                statement.setInt(1, blockingPid)
                statement.setInt(2, waitingPid)
                do {
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        if (rows.getBoolean(1)) return@connection
                    }
                } while (System.nanoTime() < deadline)
                error("second publication writer did not wait for the first writer row lock")
            }
        }
    }

    private fun firstPublication(postId: PostId): Instant? = transaction(database) {
        PostTable.selectAll().where { PostTable.postId eq postId.value }.single()[PostTable.firstPublishedAt]?.toInstant()
    }

    private fun insertPost(
        postId: PostId,
        status: PostAvailabilityStatus? = PostAvailabilityStatus.PUBLISHED,
        snapshot: Boolean = true,
        firstPublishedAt: Instant? = observedAt,
    ) {
        val title = "Post ${postId.value}"
        repository.saveIdentity(binding(postId), title, observedAt)
        if (snapshot) repository.saveSnapshot(Post(postId, title, BlockTree(emptyList())), SourceRevision("revision"), observedAt)
        if (status != null) repository.saveAvailability(PostAvailability(postId, status, observedAt))
        if (firstPublishedAt != null) repository.recordFirstPublication(postId, firstPublishedAt)
    }

    private fun createPublication(publicationId: PublicationId, revisionId: UUID, root: PostId) {
        PublicationTable.insert { it[PublicationTable.publicationId] = publicationId.value }
        insertRevision(publicationId, revisionId, "ACTIVE")
        insertMember(revisionId, root)
        PublicationTable.update({ PublicationTable.publicationId eq publicationId.value }) {
            it[rootPostId] = root.value
            it[activeRevisionId] = revisionId
        }
    }

    private fun insertRevision(publicationId: PublicationId, revisionId: UUID, state: String) {
        PublicationRevisionTable.insert {
            it[PublicationRevisionTable.revisionId] = revisionId
            it[PublicationRevisionTable.publicationId] = publicationId.value
            it[PublicationRevisionTable.state] = state
            it[startedAt] = observedAt.atOffset(ZoneOffset.UTC)
            it[activatedAt] = if (state == "ACTIVE" || state == "SUPERSEDED") observedAt.atOffset(ZoneOffset.UTC) else null
        }
    }

    private fun insertMember(revisionId: UUID, postId: PostId, parentPostId: PostId? = null) {
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

    private fun connection(block: (Connection) -> Unit) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)
    }
}
