package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint

internal const val ADMIN_ROLE = "ROLE_ADMIN"
internal const val ADMIN_REGISTRATION_ID = "admin"

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminSecurityProperties::class)
internal class AdminSecurityConfig {
    // 관리·로그인 경로만 이 체인을 통과하므로 공개 읽기는 보안 필터 영향을 받지 않는다. 설정이 없으면 /admin/**은 403이다.
    @Bean
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        properties: AdminSecurityProperties,
        registrations: ClientRegistrationRepository,
        authoritiesMapper: GrantedAuthoritiesMapper,
    ): SecurityFilterChain {
        http.securityMatcher("/admin/**", "/oauth2/**", "/login/oauth2/**")
        http.authorizeHttpRequests { requests ->
            if (properties.enabled) {
                requests.requestMatchers("/admin/**").hasAuthority(ADMIN_ROLE)
                requests.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
            }
            requests.anyRequest().denyAll()
        }
        // 응답 헤더는 WebSecurityHeadersFilter가 정한다.
        http.headers { headers -> headers.disable() }
        http.logout { logout -> logout.logoutUrl("/admin/logout").logoutSuccessUrl("/") }
        if (properties.enabled) {
            http.oauth2Login { login ->
                login.clientRegistrationRepository(registrations)
                login.userInfoEndpoint { info -> info.userAuthoritiesMapper(authoritiesMapper) }
            }
        }
        http.exceptionHandling { handling -> handling.authenticationEntryPoint(entryPoint(properties)) }
        return http.build()
    }

    private fun entryPoint(properties: AdminSecurityProperties) =
        if (properties.enabled) {
            LoginUrlAuthenticationEntryPoint("/oauth2/authorization/$ADMIN_REGISTRATION_ID")
        } else {
            HttpStatusEntryPoint(HttpStatus.FORBIDDEN)
        }

    // 발급자와 subject가 모두 설정값과 같은 OIDC 사용자에게만 관리자 권한을 준다.
    @Bean
    fun adminAuthoritiesMapper(properties: AdminSecurityProperties): GrantedAuthoritiesMapper =
        GrantedAuthoritiesMapper { authorities ->
            val admitted =
                authorities.filterIsInstance<OidcUserAuthority>().any { authority ->
                    properties.oidc.allows(authority.idToken.issuer?.toString(), authority.idToken.subject)
                }
            if (admitted) setOf<GrantedAuthority>(SimpleGrantedAuthority(ADMIN_ROLE)) else emptySet()
        }

    // 시작 시 발급자 메타데이터를 가져오지 않도록 첫 로그인 요청에서 등록 정보를 해석한다.
    @Bean
    fun adminClientRegistrations(properties: AdminSecurityProperties): ClientRegistrationRepository {
        val registration: ClientRegistration by lazy {
            ClientRegistrations
                .fromIssuerLocation(properties.oidc.issuerUri)
                .registrationId(ADMIN_REGISTRATION_ID)
                .clientId(properties.oidc.clientId)
                .clientSecret(properties.oidc.clientSecret)
                .scope("openid")
                .build()
        }
        return ClientRegistrationRepository { id ->
            if (properties.enabled && id == ADMIN_REGISTRATION_ID) registration else null
        }
    }
}
