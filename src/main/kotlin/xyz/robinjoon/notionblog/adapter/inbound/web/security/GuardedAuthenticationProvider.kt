package xyz.robinjoon.notionblog.adapter.inbound.web.security

import org.springframework.security.authentication.LockedException
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.password.PasswordEncoder

internal class GuardedAuthenticationProvider(
    users: UserDetailsService,
    encoder: PasswordEncoder,
    private val guard: LoginAttemptGuard,
) : DaoAuthenticationProvider(users) {
    init {
        setPasswordEncoder(encoder)
    }

    // LockedException은 계정 상태 실패라 ProviderManager가 그대로 던진다. 잠금 검사가 없는 부모 매니저로 넘어가지 않는다.
    override fun authenticate(authentication: Authentication): Authentication {
        if (guard.isLocked()) {
            throw LockedException("Too many failed login attempts")
        }
        return super.authenticate(authentication)
    }
}
