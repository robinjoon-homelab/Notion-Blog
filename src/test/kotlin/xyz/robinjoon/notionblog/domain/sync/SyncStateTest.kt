package xyz.robinjoon.notionblog.domain.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SyncStateTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")

    @Test
    fun `a source failure updates sync state without representing unpublished publication state`() {
        val initial =
            SyncState(
                target = SyncTarget.Post(PostId(UUID.fromString("27a3f37c-f408-4e1f-b11c-4069a669ecd7"))),
                lastSuccessAt = now,
                refreshAfter = now.plusSeconds(60),
                failureCount = 0,
                lastErrorKind = null,
            )

        val failed =
            initial.recordFailure(
                kind = SyncFailureKind.RETRYABLE_SOURCE,
                refreshAfter = now.plusSeconds(120),
            )

        assertThat(failed.lastSuccessAt).isEqualTo(now)
        assertThat(failed.failureCount).isEqualTo(1)
        assertThat(failed.lastErrorKind).isEqualTo(SyncFailureKind.RETRYABLE_SOURCE)
    }

    @Test
    fun `a successful synchronization resets failure tracking`() {
        val failed =
            SyncState(
                target = SyncTarget.SiteConfiguration,
                lastSuccessAt = null,
                refreshAfter = now,
                failureCount = 2,
                lastErrorKind = SyncFailureKind.MAPPING,
            )

        val succeeded = failed.recordSuccess(now, now.plusSeconds(60))

        assertThat(succeeded.lastSuccessAt).isEqualTo(now)
        assertThat(succeeded.failureCount).isZero()
        assertThat(succeeded.lastErrorKind).isNull()
    }

    @Test
    fun `sync state rejects a negative failure count`() {
        assertThatIllegalArgumentException().isThrownBy {
            SyncState(SyncTarget.SiteConfiguration, null, now, failureCount = -1, lastErrorKind = null)
        }
    }

    @Test
    fun `sync state keeps failure count and failure kind together`() {
        assertThatIllegalArgumentException().isThrownBy {
            SyncState(SyncTarget.SiteConfiguration, null, now, failureCount = 0, lastErrorKind = SyncFailureKind.MAPPING)
        }
        assertThatIllegalArgumentException().isThrownBy {
            SyncState(SyncTarget.SiteConfiguration, null, now, failureCount = 1, lastErrorKind = null)
        }
    }

    @Test
    fun `sync targets distinguish site configuration publications and posts by typed identity`() {
        assertThat(SyncTarget.Publication(PublicationId(UUID.fromString("076a94c6-6b0a-4c41-84a3-4882e3117aa8"))))
            .isNotEqualTo(SyncTarget.SiteConfiguration)
    }

    @Test
    fun `refresh policy uses the success interval and capped exponential failure delay`() {
        val policy =
            RefreshPolicy(
                successInterval = Duration.ofMinutes(10),
                initialFailureDelay = Duration.ofMinutes(2),
                maximumFailureDelay = Duration.ofMinutes(8),
            )

        assertThat(policy.nextSuccessfulRefreshAt(now)).isEqualTo(now.plus(Duration.ofMinutes(10)))
        assertThat(policy.nextFailureRefreshAt(now, failureCount = 1)).isEqualTo(now.plus(Duration.ofMinutes(2)))
        assertThat(policy.nextFailureRefreshAt(now, failureCount = 4)).isEqualTo(now.plus(Duration.ofMinutes(8)))
    }
}
