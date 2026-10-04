package xyz.robinjoon.notionblog.adapter.output.persistence.exposed.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.ExposedSyncStateRepository
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedSyncStateRepositoryIntegrationTest {
    private val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private lateinit var database: Database
    private val repository = ExposedSyncStateRepository()

    @BeforeAll
    fun migrate() {
        container.start()
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        database = Database.connect(container.jdbcUrl, driver = "org.postgresql.Driver", user = container.username, password = container.password)
    }

    @AfterAll
    fun stopDatabase() {
        container.stop()
    }

    @BeforeEach
    fun clearDatabase() {
        connection { connection -> connection.createStatement().use { it.execute("truncate table sync_state") } }
    }

    @Test
    fun `round trips source-neutral singleton and UUID sync targets with enum failure kinds`() = inTransaction {
        val now = Instant.parse("2026-08-25T01:02:03Z")
        val states = listOf(
            SyncState(SyncTarget.SiteConfiguration, now, now.plusSeconds(10), 0, null),
            SyncState(SyncTarget.Publication(PublicationId(UUID.randomUUID())), null, now.plusSeconds(20), 1, SyncFailureKind.ACCESS),
            SyncState(SyncTarget.Post(PostId(UUID.randomUUID())), now, now.plusSeconds(30), 2, SyncFailureKind.MAPPING),
        )

        states.forEach(repository::save)

        states.forEach { state -> assertThat(repository.find(state.target)).isEqualTo(state) }
    }

    @Test
    fun `returns due sync states in refresh order and honors the requested limit`() = inTransaction {
        val now = Instant.parse("2026-08-25T01:02:03Z")
        val first = SyncState(SyncTarget.SiteConfiguration, null, now.minusSeconds(30), 0, null)
        val second = SyncState(SyncTarget.Publication(PublicationId(UUID.randomUUID())), null, now.minusSeconds(20), 1, SyncFailureKind.RETRYABLE_SOURCE)
        val third = SyncState(SyncTarget.Post(PostId(UUID.randomUUID())), null, now.minusSeconds(10), 1, SyncFailureKind.AUTHENTICATION)
        val later = SyncState(SyncTarget.Post(PostId(UUID.randomUUID())), null, now.plusSeconds(1), 0, null)
        listOf(third, later, first, second).forEach(repository::save)

        assertThat(repository.findDue(now, 2)).containsExactly(first, second)
        assertThat(repository.findDue(now, 10)).containsExactly(first, second, third)
    }

    @Test
    fun `deleting a post reservation preserves other target kinds and tolerates repeated deletion`() = inTransaction {
        val sharedId = UUID.randomUUID()
        val now = Instant.parse("2026-08-25T01:02:03Z")
        val removed = SyncState(SyncTarget.Post(PostId(sharedId)), now, now, 0, null)
        val retained = listOf(
            SyncState(SyncTarget.Publication(PublicationId(sharedId)), now, now, 0, null),
            SyncState(SyncTarget.SiteConfiguration, now, now, 0, null),
            SyncState(SyncTarget.Post(PostId(UUID.randomUUID())), now, now, 0, null),
        )
        (retained + removed).forEach(repository::save)

        repository.delete(removed.target)
        repository.delete(removed.target)

        assertThat(repository.find(removed.target)).isNull()
        assertThat(repository.findDue(now, 10)).containsExactlyInAnyOrderElementsOf(retained)
    }

    @Test
    fun `upserts an existing target state`() = inTransaction {
        val target = SyncTarget.Post(PostId(UUID.randomUUID()))
        val first = SyncState(target, null, Instant.parse("2026-08-25T01:02:03Z"), 0, null)
        val replacement = SyncState(target, Instant.parse("2026-08-25T01:03:03Z"), Instant.parse("2026-08-25T02:03:03Z"), 1, SyncFailureKind.CONFIGURATION)

        repository.save(first)
        repository.save(replacement)

        assertThat(repository.find(target)).isEqualTo(replacement)
    }

    @Test
    fun `lets PostgreSQL enforce failure error pairing and rolls back caller writes`() {
        connection { connection ->
            assertThatThrownBy {
                connection.createStatement().use {
                    it.execute(
                        "insert into sync_state (target_kind, target_key, refresh_after, failure_count, last_error_kind) " +
                            "values ('POST', 'invalid-pair', now(), 0, 'MAPPING')",
                    )
                }
            }.hasMessageContaining("sync_state_failure_error_pair_check")
        }

        val state = SyncState(SyncTarget.SiteConfiguration, null, Instant.parse("2026-08-25T01:02:03Z"), 0, null)
        assertThatThrownBy {
            transaction(database) {
                repository.save(state)
                error("caller failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        inTransaction {
            assertThat(repository.find(state.target)).isNull()
        }
    }

    private fun inTransaction(block: () -> Unit) {
        transaction(database) { block() }
    }

    private fun connection(block: (Connection) -> Unit) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)
    }
}
