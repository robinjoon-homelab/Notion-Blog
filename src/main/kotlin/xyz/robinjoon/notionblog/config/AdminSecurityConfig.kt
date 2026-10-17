package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint

internal const val ADMIN_ROLE = "ROLE_ADMIN"

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminSecurityProperties::class)
internal class AdminSecurityConfig {
    // 관리 경로만 이 체인을 통과하므로 공개 읽기는 보안 필터 영향을 받지 않는다. 설정이 없으면 /admin/**은 로그인 화면까지 403이다.
    @Bean
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        properties: AdminSecurityProperties,
    ): SecurityFilterChain {
        http.securityMatcher("/admin/**")
        http.authorizeHttpRequests { requests ->
            if (properties.enabled) {
                // 로그인 화면과 처리 경로는 formLogin의 permitAll이 열어 준다.
                requests.requestMatchers("/admin/**").hasAuthority(ADMIN_ROLE)
            }
            requests.anyRequest().denyAll()
        }
        // 응답 헤더는 WebSecurityHeadersFilter가 정한다.
        http.headers { headers -> headers.disable() }
        http.logout { logout -> logout.logoutUrl("/admin/logout").logoutSuccessUrl("/") }
        if (properties.enabled) {
            http.formLogin { form ->
                form
                    .loginPage("/admin/login")
                    .loginProcessingUrl("/admin/login")
                    .defaultSuccessUrl("/admin/posts", true)
                    .failureUrl("/admin/login?error")
                    .permitAll()
            }
        } else {
            http.exceptionHandling { handling -> handling.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.FORBIDDEN)) }
        }
        return http.build()
    }

    @Bean
    fun adminPasswordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    // 설정이 비어도 빈을 두어 Spring Boot가 임시 비밀번호의 기본 계정을 만들지 못하게 한다.
    @Bean
    fun adminUserDetailsService(
        properties: AdminSecurityProperties,
        passwordEncoder: PasswordEncoder,
    ): UserDetailsService =
        if (properties.enabled) {
            InMemoryUserDetailsManager(
                User
                    .withUsername(properties.username)
                    .password(passwordEncoder.encode(properties.password))
                    .authorities(ADMIN_ROLE)
                    .build(),
            )
        } else {
            InMemoryUserDetailsManager()
        }
}
