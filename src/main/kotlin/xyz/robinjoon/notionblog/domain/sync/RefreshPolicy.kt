package xyz.robinjoon.notionblog.domain.sync

import java.time.Duration
import java.time.Instant

data class RefreshPolicy(
    val successInterval: Duration,
    val initialFailureDelay: Duration,
    val maximumFailureDelay: Duration,
) {
    init {
        require(successInterval.isPositive) { "success refresh interval must be positive" }
        require(initialFailureDelay.isPositive) { "initial failure delay must be positive" }
        require(maximumFailureDelay >= initialFailureDelay) {
            "maximum failure delay must not be shorter than the initial failure delay"
        }
    }

    fun nextSuccessfulRefreshAt(now: Instant): Instant = now.plus(successInterval)

    fun nextFailureRefreshAt(
        now: Instant,
        failureCount: Int,
    ): Instant {
        require(failureCount > 0) { "failure count must be positive" }
        return now.plus(failureDelay(failureCount))
    }

    private fun failureDelay(failureCount: Int): Duration {
        var delay = initialFailureDelay
        repeat(failureCount - 1) {
            // Compare before doubling so very large durations reach the cap without overflow.
            if (delay >= maximumFailureDelay.minus(delay)) {
                return maximumFailureDelay
            }
            delay = delay.multipliedBy(2)
        }
        return delay
    }
}
