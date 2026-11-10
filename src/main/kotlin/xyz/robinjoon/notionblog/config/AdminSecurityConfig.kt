package xyz.robinjoon.notionblog.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.LockedException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.DefaultRedirectStrategy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint
import xyz.robinjoon.notionblog.adapter.inbound.web.security.GuardedAuthenticationProvider
import xyz.robinjoon.notionblog.adapter.inbound.web.security.LoginAttemptEvents
import xyz.robinjoon.notionblog.adapter.inbound.web.security.LoginAttemptGuard
import java.time.Clock
import java.time.Duration

internal const val ADMIN_ROLE = "ROLE_ADMIN"
internal const val ADMIN_LOGIN_PATH = "/admin/login"

private const val MAX_LOGIN_FAILURES = 5
private const val LOCK_MINUTES = 10L

private val log = LoggerFactory.getLogger(AdminSecurityConfig::class.java)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminSecurityProperties::class)
internal class AdminSecurityConfig(
    private val properties: AdminSecurityProperties,
) {
    @Bean
    fun adminPasswordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    // 설정이 없어도 빈을 두어 Spring Boot가 임시 비밀번호의 기본 계정을 만들지 못하게 한다.
    @Bean
    fun adminUserDetailsService(encoder: PasswordEncoder): UserDetailsService {
        if (!properties.enabled) {
            log.warn("Admin pages are closed: {}", properties.closedReason)
            return InMemoryUserDetailsManager()
        }
        val admin =
            User
                .withUsername(properties.username)
                .password(storedPassword(properties.password, encoder))
                .authorities(ADMIN_ROLE)
                .build()
        return InMemoryUserDetailsManager(admin)
    }

    @Bean
    fun loginAttemptGuard(clock: Clock): LoginAttemptGuard = LoginAttemptGuard(MAX_LOGIN_FAILURES, Duration.ofMinutes(LOCK_MINUTES), clock)

    @Bean
    fun loginAttemptEvents(guard: LoginAttemptGuard): LoginAttemptEvents = LoginAttemptEvents(guard)

    // 이 체인에는 관리 경로와 생성 문서만 들어오므로 공개 읽기는 보안 필터 영향을 받지 않는다. 설정이 없으면 모두 403이다.
    @Bean
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        users: UserDetailsService,
        encoder: PasswordEncoder,
        guard: LoginAttemptGuard,
    ): SecurityFilterChain {
        http.securityMatcher("/admin/**", "/docs/**")
        http.authorizeHttpRequests { requests ->
            if (properties.enabled) {
                requests.requestMatchers(ADMIN_LOGIN_PATH).permitAll()
                requests.requestMatchers("/admin/**", "/docs/**").hasAuthority(ADMIN_ROLE)
            }
            requests.anyRequest().denyAll()
        }
        // 응답 헤더는 체인 밖의 응답에도 붙도록 WebSecurityHeadersFilter가 정한다.
        http.headers { it.disable() }
        http.logout { it.logoutUrl("/admin/logout").logoutSuccessUrl("$ADMIN_LOGIN_PATH?logout") }
        if (properties.enabled) {
            configureLogin(http, GuardedAuthenticationProvider(users, encoder, guard))
        } else {
            // 이 진입점은 sendError를 호출해 오류 화면까지 가며, 상태만 설정하면 본문이 비어 버린다.
            http.exceptionHandling { it.authenticationEntryPoint(Http403ForbiddenEntryPoint()) }
        }
        return http.build()
    }

    private fun configureLogin(
        http: HttpSecurity,
        provider: AuthenticationProvider,
    ) {
        http.authenticationProvider(provider)
        http.formLogin {
            it.loginPage(ADMIN_LOGIN_PATH)
            it.loginProcessingUrl(ADMIN_LOGIN_PATH)
            it.defaultSuccessUrl("/admin", false)
            it.failureHandler(redirectByFailure())
        }
        http.exceptionHandling { it.accessDeniedHandler(redirectStaleSessionToLogin()) }
    }

    // 세션 만료 뒤에 제출된 폼은 CSRF 토큰을 잃어 막다른 403 화면으로 끝나므로 로그인으로 보낸다.
    private fun redirectStaleSessionToLogin(): AccessDeniedHandler {
        val redirects = DefaultRedirectStrategy()
        val denied = AccessDeniedHandlerImpl()
        return AccessDeniedHandler { request, response, exception ->
            if (request.requestedSessionId != null && !request.isRequestedSessionIdValid) {
                redirects.sendRedirect(request, response, ADMIN_LOGIN_PATH)
            } else {
                denied.handle(request, response, exception)
            }
        }
    }

    private fun redirectByFailure(): AuthenticationFailureHandler {
        val redirects = DefaultRedirectStrategy()
        return AuthenticationFailureHandler { request, response, failure ->
            val state = if (failure is LockedException) "locked" else "error"
            redirects.sendRedirect(request, response, "$ADMIN_LOGIN_PATH?$state")
        }
    }
}

// bcrypt만 저장된 값으로 본다. 다른 {id}를 그대로 두면 그 id를 해석하는 인코더가 없어 모든 로그인이 실패한다.
internal fun storedPassword(
    raw: String,
    encoder: PasswordEncoder,
): String =
    if (raw.startsWith(BCRYPT_PREFIX)) {
        raw
    } else {
        requireNotNull(encoder.encode(raw)) { "The password encoder returned no value." }
    }
