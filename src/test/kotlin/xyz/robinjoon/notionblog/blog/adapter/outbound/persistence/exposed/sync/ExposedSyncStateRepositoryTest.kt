package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedSyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailure
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.blog.domain.sync.SyncState
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import java.time.Instant
import java.util.UUID

class ExposedSyncStateRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedSyncStateRepository()

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `round trips source-neutral singleton and UUID sync targets with enum failure kinds`() =
        inTransaction {
            val now = Instant.parse("2026-08-25T01:02:03Z")
            val states =
                listOf(
                    SyncState.restore(SyncTarget.SiteConfiguration, now, now.plusSeconds(10), null),
                    SyncState.restore(
                        SyncTarget.Publication(PublicationId(UUID.randomUUID())),
                        null,
                        now.plusSeconds(20),
                        SyncFailure(1, SyncFailureKind.ACCESS),
                    ),
                    SyncState.restore(
                        SyncTarget.Post(PostId(UUID.randomUUID())),
                        now,
                        now.plusSeconds(30),
                        SyncFailure(2, SyncFailureKind.MAPPING),
                    ),
                )

            states.forEach(repository::save)

            states.forEach { state -> assertThat(repository.find(state.target)).isEqualTo(state) }
        }

    @Test
    fun `returns due sync states in refresh order and honors the requested limit`() =
        inTransaction {
            val now = Instant.parse("2026-08-25T01:02:03Z")
            val first = SyncState.restore(SyncTarget.SiteConfiguration, null, now.minusSeconds(30), null)
            val second =
                SyncState.restore(
                    SyncTarget.Publication(PublicationId(UUID.randomUUID())),
                    null,
                    now.minusSeconds(20),
                    SyncFailure(1, SyncFailureKind.RETRYABLE_SOURCE),
                )
            val third =
                SyncState.restore(
                    SyncTarget.Post(PostId(UUID.randomUUID())),
                    null,
                    now.minusSeconds(10),
                    SyncFailure(1, SyncFailureKind.AUTHENTICATION),
                )
            val later = SyncState.restore(SyncTarget.Post(PostId(UUID.randomUUID())), null, now.plusSeconds(1), null)
            listOf(third, later, first, second).forEach(repository::save)

            assertThat(repository.findDue(now, 2)).containsExactly(first, second)
            assertThat(repository.findDue(now, 10)).containsExactly(first, second, third)
        }

    @Test
    fun `deleting a post reservation preserves other target kinds and tolerates repeated deletion`() =
        inTransaction {
            val sharedId = UUID.randomUUID()
            val now = Instant.parse("2026-08-25T01:02:03Z")
            val removed = SyncState.restore(SyncTarget.Post(PostId(sharedId)), now, now, null)
            val retained =
                listOf(
                    SyncState.restore(SyncTarget.Publication(PublicationId(sharedId)), now, now, null),
                    SyncState.restore(SyncTarget.SiteConfiguration, now, now, null),
                    SyncState.restore(SyncTarget.Post(PostId(UUID.randomUUID())), now, now, null),
                )
            (retained + removed).forEach(repository::save)

            repository.delete(removed.target)
            repository.delete(removed.target)

            assertThat(repository.find(removed.target)).isNull()
            assertThat(repository.findDue(now, 10)).containsExactlyInAnyOrderElementsOf(retained)
        }

    @Test
    fun `upserts an existing target state`() =
        inTransaction {
            val target = SyncTarget.Post(PostId(UUID.randomUUID()))
            val first = SyncState.restore(target, null, Instant.parse("2026-08-25T01:02:03Z"), null)
            val replacement =
                SyncState.restore(
                    target,
                    Instant.parse("2026-08-25T01:03:03Z"),
                    Instant.parse("2026-08-25T02:03:03Z"),
                    SyncFailure(1, SyncFailureKind.CONFIGURATION),
                )

            repository.save(first)
            repository.save(replacement)

            assertThat(repository.find(target)).isEqualTo(replacement)
        }

    private fun inTransaction(block: () -> Unit) {
        database.inTransaction(block)
    }
}
