package xyz.robinjoon.notionblog.domain.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

class RefreshPolicyTest {
    @Test
    fun `failure delay doubles precisely until an odd nanosecond cap is reached`() {
        val policy = RefreshPolicy(Duration.ofMinutes(1), Duration.ofNanos(2), Duration.ofNanos(9))

        assertThat(policy.nextFailureRefreshAt(Instant.EPOCH, 1)).isEqualTo(Instant.EPOCH.plusNanos(2))
        assertThat(policy.nextFailureRefreshAt(Instant.EPOCH, 2)).isEqualTo(Instant.EPOCH.plusNanos(4))
        assertThat(policy.nextFailureRefreshAt(Instant.EPOCH, 3)).isEqualTo(Instant.EPOCH.plusNanos(8))
        assertThat(policy.nextFailureRefreshAt(Instant.EPOCH, 4)).isEqualTo(Instant.EPOCH.plusNanos(9))
    }

    @Test
    fun `failure delay stays at its cap for the largest failure count`() {
        val maximum = Duration.ofSeconds(3)
        val policy = RefreshPolicy(Duration.ofMinutes(1), Duration.ofNanos(1), maximum)

        assertThat(policy.nextFailureRefreshAt(Instant.EPOCH, Int.MAX_VALUE)).isEqualTo(Instant.EPOCH.plus(maximum))
    }

    @Test
    fun `an initial delay equal to its cap stays unchanged`() {
        val delay = Duration.ofNanos(1)
        val policy = RefreshPolicy(Duration.ofMinutes(1), delay, delay)

        assertThat(policy.nextFailureRefreshAt(Instant.EPOCH, 1)).isEqualTo(Instant.EPOCH.plus(delay))
        assertThat(policy.nextFailureRefreshAt(Instant.EPOCH, 2)).isEqualTo(Instant.EPOCH.plus(delay))
    }

    @Test
    fun `overflowing exponential delay reaches the cap before validating the resulting instant`() {
        val initial = Duration.ofSeconds(Long.MAX_VALUE / 2 + 1)
        val policy = RefreshPolicy(Duration.ofMinutes(1), initial, Duration.ofSeconds(Long.MAX_VALUE))

        assertThatExceptionOfType(DateTimeException::class.java).isThrownBy {
            policy.nextFailureRefreshAt(Instant.EPOCH, 2)
        }
    }
}
