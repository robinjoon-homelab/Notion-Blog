package xyz.robinjoon.notionblog.adapter.inbound.web.security

import java.time.Clock
import java.time.Duration
import java.time.Instant

// 보호할 운영자 계정이 하나뿐이라 실패 횟수와 잠금을 애플리케이션 전체에서 센다.
internal class LoginAttemptGuard(
    private val maxFailures: Int,
    private val lockDuration: Duration,
    private val clock: Clock,
) {
    private var failures = 0
    private var lockedUntil: Instant? = null

    @Synchronized
    fun isLocked(): Boolean {
        val until = lockedUntil ?: return false
        if (clock.instant().isBefore(until)) {
            return true
        }
        clear()
        return false
    }

    @Synchronized
    fun recordFailure() {
        if (isLocked()) {
            return
        }
        failures += 1
        if (failures >= maxFailures) {
            lockedUntil = clock.instant().plus(lockDuration)
        }
    }

    @Synchronized
    fun recordSuccess() = clear()

    private fun clear() {
        failures = 0
        lockedUntil = null
    }
}
