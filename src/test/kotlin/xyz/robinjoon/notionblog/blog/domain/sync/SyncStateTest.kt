package xyz.robinjoon.notionblog.blog.domain.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SyncStateTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")

    @Test
    fun `a source failure updates sync state without representing unpublished publication state`() {
        val initial =
            SyncState.restore(
                target = SyncTarget.Post(PostId(UUID.fromString("27a3f37c-f408-4e1f-b11c-4069a669ecd7"))),
                lastSuccessAt = now,
                refreshAfter = now.plusSeconds(60),
                failure = null,
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
    fun `a failure record rejects a count that is not positive`() {
        assertThatIllegalArgumentException().isThrownBy { SyncFailure(-1, SyncFailureKind.MAPPING) }
        assertThatIllegalArgumentException().isThrownBy { SyncFailure(0, SyncFailureKind.MAPPING) }
    }

    @Test
    fun `refresh policy caps the exponential failure delay`() {
        val policy =
            RefreshPolicy(
                successInterval = Duration.ofMinutes(10),
                initialFailureDelay = Duration.ofMinutes(2),
                maximumFailureDelay = Duration.ofMinutes(8),
            )

        assertThat(policy.nextFailureRefreshAt(now, failureCount = 1)).isEqualTo(now.plus(Duration.ofMinutes(2)))
        assertThat(policy.nextFailureRefreshAt(now, failureCount = 4)).isEqualTo(now.plus(Duration.ofMinutes(8)))
    }
}
