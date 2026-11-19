package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.migration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedMigrationJobRepository
import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationJob
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobAction
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobCounts
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobItem
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobLease
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobSummary
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobUpdates
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import java.time.Instant
import java.util.UUID

class ExposedMigrationJobRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedMigrationJobRepository()
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val selection = MigrationSelection("data-source", MigrationFields("title", "summary", "tags", "date"))
    private val job = MigrationJob(UUID.randomUUID(), now, selection)
    private val first = MigrationEntry(PostFixtures.document("first"), "첫 글")
    private val second = MigrationEntry(PostFixtures.document("second"), "다음 글")

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `history pages order jobs by creation and id and include empty jobs`() {
        val empty = MigrationJob(UUID.randomUUID(), now.plusSeconds(1), selection)
        val sameTime = job.copy(id = UUID(0, 1))
        val laterId = job.copy(id = UUID(0, 2))
        val emptySummary = MigrationJobSummary(empty.id, empty.createdAt, 0, 0, false)
        val firstSummary = MigrationJobSummary(sameTime.id, now, 2, 2, false)
        val secondSummary = MigrationJobSummary(laterId.id, now, 1, 1, false)
        database.inTransaction {
            repository.create(sameTime, listOf(first, second))
            repository.create(laterId, listOf(second))
            repository.create(empty, emptyList())
        }

        database.inTransaction {
            assertThat(repository.recent(2, 0, now)).containsExactly(emptySummary, secondSummary)
            assertThat(repository.recent(2, 1, now)).containsExactly(secondSummary, firstSummary)
            assertThat(repository.recent(2, 2, now)).containsExactly(firstSummary)
            assertThat(repository.recent(2, 3, now)).isEmpty()
            assertThat(repository.recent(0, 0, now)).isEmpty()
            assertThat(requireNotNull(repository.find(empty.id, now)).items).isEmpty()
            val emptyUpdates = MigrationJobUpdates(emptySummary, 0, MigrationJobCounts(), emptyList())
            assertThat(repository.updates(empty.id, 0, now)).isEqualTo(emptyUpdates)
            assertThat(repository.find(UUID.randomUUID(), now)).isNull()
            assertThat(repository.updates(UUID.randomUUID(), 0, now)).isNull()
            assertThat(repository.change(UUID.randomUUID(), MigrationJobAction.RESUME)).isFalse()
            assertThat(repository.change(UUID.randomUUID(), MigrationJobAction.RETRY_FAILED)).isFalse()
        }
    }

    @Test
    fun `queue creation replaces null characters in display titles`() {
        database.inTransaction { repository.create(job, listOf(first.copy(title = "앞\u0000뒤"))) }

        database.inTransaction {
            val item = requireNotNull(repository.find(job.id, now)).items.single()
            assertThat(item.entry).isEqualTo(first.copy(title = "앞\uFFFD뒤"))
        }
    }

    @Test
    fun `pause keeps an active item and resume continues with the next pending item`() {
        database.inTransaction {
            repository.create(job, listOf(second, first))
        }
        val claim = database.inTransaction { requireNotNull(repository.claim(lease())) }
        val completed = MigratePostResult.Skipped(PostFixtures.postId(1))

        database.inTransaction {
            assertThat(claim.command).isEqualTo(MigratePostCommand(selection, second.sourceDocument))
            assertThat(repository.claim(lease())).isNull()
            assertThat(repository.change(job.id, MigrationJobAction.PAUSE)).isTrue()
            val progress = requireNotNull(repository.find(job.id, now))
            assertThat(progress.summary.paused).isTrue()
            assertThat(progress.summary.processing).isTrue()
            assertThat(repository.recent(1, 0, now).single()).isEqualTo(progress.summary)
            assertThat(progress.items).containsExactly(MigrationJobItem(0, second, processing = true), MigrationJobItem(1, first))
            val extension = now.plusSeconds(20)
            repository.renewClaim(claim, extension)
            repository.renewClaim(claim, now.plusSeconds(11))
            repository.renewClaim(claim.copy(jobId = UUID.randomUUID()), extension.plusSeconds(10))
            repository.renewClaim(claim.copy(position = 1), extension.plusSeconds(10))
            val draining = updates(0, extension.minusMillis(1))
            assertThat(draining.version).isZero()
            assertThat(draining.summary).isEqualTo(progress.summary)
            assertThat(draining.items).containsExactly(MigrationJobItem(0, second, processing = true))
            val expired = updates(0, extension)
            assertThat(expired.summary.processing).isTrue()
            assertThat(expired.items).containsExactly(MigrationJobItem(0, second, processing = true))
            repository.complete(claim, completed)
            assertThat(repository.claim(lease(extension))).isNull()
        }

        database.inTransaction {
            assertThat(requireNotNull(repository.find(job.id, now)).items.first().result).isEqualTo(completed)
            assertThat(repository.change(job.id, MigrationJobAction.RESUME)).isTrue()
            assertThat(requireNotNull(repository.claim(lease())).position).isEqualTo(1)
        }
    }

    @Test
    fun `abandoned paused work stops draining when the expired token is fenced`() {
        val lease = lease()
        database.inTransaction { repository.create(job, listOf(first)) }
        val claim = database.inTransaction { requireNotNull(repository.claim(lease)) }

        database.inTransaction {
            assertThat(repository.change(job.id, MigrationJobAction.PAUSE)).isTrue()
            assertThat(updates(0, lease.expiresAt).summary.processing).isTrue()
            assertThat(repository.claim(lease(lease.expiresAt))).isNull()
            repository.complete(claim, MigratePostResult.Imported(PostFixtures.postId(1)))
            val stopped = updates(0, lease.expiresAt)
            assertThat(stopped.summary.processing).isFalse()
            assertThat(stopped.version).isEqualTo(1L)
            assertThat(stopped.items).containsExactly(MigrationJobItem(0, first))
        }
    }

    @Test
    fun `expiry clears the processing indicator but the current token can still finalize`() {
        val lease = lease()
        database.inTransaction { repository.create(job, listOf(first)) }
        val claim = database.inTransaction { requireNotNull(repository.claim(lease)) }
        val result = MigratePostResult.Imported(PostFixtures.postId(1))

        database.inTransaction {
            val beforeExpiry = lease.expiresAt.minusMillis(1)
            assertThat(requireNotNull(repository.find(job.id, beforeExpiry)).summary.processing).isTrue()
            assertThat(repository.recent(1, 0, beforeExpiry).single().processing).isTrue()
            val expired = requireNotNull(repository.find(job.id, lease.expiresAt))
            assertThat(expired.summary.processing).isFalse()
            assertThat(expired.items.single().processing).isFalse()
            assertThat(repository.recent(1, 0, lease.expiresAt).single()).isEqualTo(expired.summary)
            assertThat(updates(0, lease.expiresAt).items).isEqualTo(expired.items)
            assertThat(repository.lockClaim(claim.copy(jobId = UUID.randomUUID()))).isFalse()
            assertThat(repository.lockClaim(claim.copy(position = 1))).isFalse()
            assertThat(repository.lockClaim(claim)).isTrue()
            repository.complete(claim, result)
        }

        database.inTransaction {
            assertThat(repository.lockClaim(claim)).isFalse()
            assertThat(requireNotNull(repository.find(job.id, lease.expiresAt)).items.single().result).isEqualTo(result)
        }
    }

    @Test
    fun `expired lease survives reopening and its late result cannot replace the new worker's result`() {
        database.inTransaction { repository.create(job, listOf(first)) }
        val oldClaim = database.inTransaction { requireNotNull(repository.claim(lease())) }
        val expiresAt = now.plusSeconds(10)
        val reopened = ExposedMigrationJobRepository()
        val newClaim =
            database.inTransaction {
                assertThat(requireNotNull(reopened.find(job.id, expiresAt)).items.single().processing).isFalse()
                requireNotNull(reopened.claim(lease(expiresAt)))
            }
        val success = MigratePostResult.Imported(PostFixtures.postId(2))

        database.inTransaction {
            assertThat(newClaim.command).isEqualTo(oldClaim.command)
            assertThat(repository.lockClaim(oldClaim)).isFalse()
            assertThat(reopened.lockClaim(newClaim)).isTrue()
            val newExpiry = expiresAt.plusSeconds(10)
            repository.renewClaim(oldClaim, newExpiry.plusSeconds(30))
            assertThat(updates(0, newExpiry).summary.processing).isFalse()
            repository.complete(oldClaim, MigratePostResult.Failed(SyncFailureKind.MEDIA))
            assertThat(requireNotNull(reopened.find(job.id, expiresAt)).items.single())
                .isEqualTo(MigrationJobItem(0, first, processing = true))
            reopened.complete(newClaim, success)
        }

        database.inTransaction {
            repository.complete(oldClaim, MigratePostResult.Failed(SyncFailureKind.ACCESS))
            assertThat(requireNotNull(reopened.find(job.id, expiresAt)).items.single().result).isEqualTo(success)
            assertThat(reopened.claim(lease(expiresAt))).isNull()
            assertThat(updates(0, expiresAt).version).isEqualTo(1)
            assertThat(updates(0, expiresAt).counts).isEqualTo(MigrationJobCounts(imported = 1))
        }
    }

    @Test
    fun `retry resets only failed work and keeps stored partial import diagnostics`() {
        val warning = MigrationFailureDetail(MigrationFailureStage.BODY, "권한 확인", "MissingBlock", UUID.randomUUID(), 404, 100L)
        val imported = MigratePostResult.Imported(PostFixtures.postId(3), listOf(warning))
        val third = MigrationEntry(PostFixtures.document("third"), "마지막 글")
        val failed = MigratePostResult.Failed(SyncFailureKind.MAPPING, warning)

        database.inTransaction {
            repository.create(job, listOf(first, second, third))
            repository.complete(requireNotNull(repository.claim(lease())), imported)
            repository.complete(requireNotNull(repository.claim(lease())), failed)
            repository.complete(requireNotNull(repository.claim(lease())), failed)
        }

        database.inTransaction {
            val completed = requireNotNull(repository.find(job.id, now))
            assertThat(completed.summary.remaining).isZero()
            assertThat(completed.version).isEqualTo(3)
            val change = updates(1)
            assertThat(change.version).isEqualTo(3)
            assertThat(change.counts).isEqualTo(MigrationJobCounts(partial = 1, failed = 2))
            assertThat(
                change.items,
            ).containsExactly(MigrationJobItem(1, second, result = failed), MigrationJobItem(2, third, result = failed))
            assertThat(completed.items.map(MigrationJobItem::result)).containsExactly(imported, failed, failed)
            assertThat(repository.change(job.id, MigrationJobAction.PAUSE)).isTrue()
            assertThat(repository.change(job.id, MigrationJobAction.RETRY_FAILED)).isTrue()
            val retried = requireNotNull(repository.find(job.id, now))
            assertThat(retried.summary).isEqualTo(MigrationJobSummary(job.id, now, 3, 2, false))
            assertThat(retried.items.map(MigrationJobItem::result)).containsExactly(imported, null, null)
            assertThat(retried.version).isEqualTo(4)
            val pending = listOf(MigrationJobItem(1, second), MigrationJobItem(2, third))
            assertThat(updates(3)).isEqualTo(MigrationJobUpdates(retried.summary, 4, MigrationJobCounts(partial = 1), pending))
            assertThat(updates(4).items).isEmpty()
            assertThat(requireNotNull(repository.claim(lease())).command.sourceDocument).isEqualTo(second.sourceDocument)
        }
    }

    @Test
    fun `stale retry after requeue and pause preserves the pause`() {
        database.inTransaction {
            repository.create(job, listOf(first))
            repository.complete(requireNotNull(repository.claim(lease())), MigratePostResult.Failed(SyncFailureKind.ACCESS))
        }
        database.inTransaction { assertThat(repository.change(job.id, MigrationJobAction.RETRY_FAILED)).isTrue() }
        database.inTransaction { assertThat(repository.change(job.id, MigrationJobAction.PAUSE)).isTrue() }

        database.inTransaction { assertThat(repository.change(job.id, MigrationJobAction.RETRY_FAILED)).isTrue() }

        database.inTransaction {
            assertThat(requireNotNull(repository.find(job.id, now)).summary).isEqualTo(MigrationJobSummary(job.id, now, 1, 1, true))
            assertThat(repository.claim(lease())).isNull()
            assertThat(updates(2).version).isEqualTo(2)
        }
    }

    @Test
    fun `retry preserves a failure while another item remains pending`() {
        val failure = MigratePostResult.Failed(SyncFailureKind.MAPPING)
        database.inTransaction {
            repository.create(job, listOf(first, second))
            repository.complete(requireNotNull(repository.claim(lease())), failure)
        }

        database.inTransaction { assertThat(repository.change(job.id, MigrationJobAction.RETRY_FAILED)).isTrue() }

        database.inTransaction {
            val progress = requireNotNull(repository.find(job.id, now))
            assertThat(progress.items.map(MigrationJobItem::result)).containsExactly(failure, null)
            assertThat(requireNotNull(repository.claim(lease())).position).isEqualTo(1)
        }
    }

    @Test
    fun `retry on a paused job with no failures preserves the pause`() {
        database.inTransaction {
            repository.create(job, listOf(first))
            repository.complete(requireNotNull(repository.claim(lease())), MigratePostResult.Skipped(PostFixtures.postId(1)))
            repository.change(job.id, MigrationJobAction.PAUSE)
        }

        database.inTransaction { assertThat(repository.change(job.id, MigrationJobAction.RETRY_FAILED)).isTrue() }

        database.inTransaction {
            assertThat(requireNotNull(repository.find(job.id, now)).summary).isEqualTo(MigrationJobSummary(job.id, now, 1, 0, true))
        }
    }

    @Test
    fun `poll sends initial rows on cursor reset and then only changed or leased positions`() {
        val result = MigratePostResult.Skipped(PostFixtures.postId(1))
        database.inTransaction {
            repository.create(job, listOf(first, second))
            val initial = requireNotNull(repository.find(job.id, now))
            assertThat(initial.version).isZero()
            assertThat(updates(-1)).isEqualTo(MigrationJobUpdates(initial.summary, 0, MigrationJobCounts(), initial.items))
            assertThat(updates(0).items).isEmpty()
            repository.complete(requireNotNull(repository.claim(lease())), result)
        }

        database.inTransaction {
            assertThat(updates(0).items).containsExactly(MigrationJobItem(0, first, result = result))
            assertThat(updates(1).items).isEmpty()
            val claim = requireNotNull(repository.claim(lease()))
            repository.renewClaim(claim, now.plusSeconds(20))
            assertThat(repository.claim(lease(now.plusSeconds(10)))).isNull()
            val active = MigrationJobItem(1, second, processing = true)
            val progress = requireNotNull(repository.find(job.id, now))
            assertThat(progress.version).isEqualTo(1)
            assertThat(updates(1)).isEqualTo(MigrationJobUpdates(progress.summary, 1, MigrationJobCounts(skipped = 1), listOf(active)))
            assertThat(updates(0).items).containsExactly(MigrationJobItem(0, first, result = result), active)
            assertThat(updates(2).items).isEqualTo(progress.items)
            assertThat(updates(-1).items).isEqualTo(progress.items)
            val reclaimed = requireNotNull(repository.claim(lease(now.plusSeconds(20))))
            assertThat(reclaimed.token).isNotEqualTo(claim.token)
            repository.renewClaim(claim, now.plusSeconds(40))
            assertThat(updates(1, now.plusSeconds(30)).items).containsExactly(MigrationJobItem(1, second))
        }
    }

    private fun updates(
        after: Long,
        at: Instant = now,
    ): MigrationJobUpdates = requireNotNull(repository.updates(job.id, after, at))

    private fun lease(at: Instant = now): MigrationJobLease = MigrationJobLease(UUID.randomUUID(), at, at.plusSeconds(10))
}
