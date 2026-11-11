package xyz.robinjoon.notionblog.adapter.inbound.web.security

import org.springframework.context.event.EventListener
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent

// 제공자 호출 횟수가 아니라 이벤트를 센다. 실패한 로그인은 부모 ProviderManager에서 한 번 더 시도되지만 이벤트는 시도당 하나만 발행된다.
internal class LoginAttemptEvents(
    private val guard: LoginAttemptGuard,
) {
    @EventListener(AuthenticationSuccessEvent::class)
    fun onSuccess() = guard.recordSuccess()

    @EventListener(AuthenticationFailureBadCredentialsEvent::class)
    fun onBadCredentials() = guard.recordFailure()
}
