package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import java.time.Instant

class AdminSecurityConfigTest {
    private val issuer = "https://id.example"
    private val oidc =
        AdminSecurityProperties.Oidc(
            issuerUri = issuer,
            clientId = "client",
            clientSecret = "secret",
            allowedSubject = "owner",
        )
    private val configured = AdminSecurityProperties(oidc)

    @Test
    fun `keeps the admin closed when nothing is configured`() {
        assertThat(AdminSecurityProperties().enabled).isFalse()
    }

    @Test
    fun `keeps the admin closed when any value is blank`() {
        assertThat(AdminSecurityProperties(oidc.copy(clientSecret = " ")).enabled).isFalse()
        assertThat(AdminSecurityProperties(oidc.copy(allowedSubject = "")).enabled).isFalse()
    }

    @Test
    fun `opens the admin only when every value is configured`() {
        assertThat(configured.enabled).isTrue()
    }

    @Test
    fun `grants admin only to the configured issuer and subject pair`() {
        val mapper = AdminSecurityConfig().adminAuthoritiesMapper(configured)

        assertThat(mapper.mapAuthorities(listOf(oidcAuthority(issuer, "owner")))).containsExactly(SimpleGrantedAuthority(ADMIN_ROLE))
        assertThat(mapper.mapAuthorities(listOf(oidcAuthority(issuer, "someone")))).isEmpty()
        assertThat(mapper.mapAuthorities(listOf(oidcAuthority("https://other.example", "owner")))).isEmpty()
        assertThat(mapper.mapAuthorities(listOf(SimpleGrantedAuthority("ROLE_USER")))).isEmpty()
    }

    @Test
    fun `grants nobody admin while unconfigured`() {
        val mapper = AdminSecurityConfig().adminAuthoritiesMapper(AdminSecurityProperties())

        assertThat(mapper.mapAuthorities(listOf(oidcAuthority(issuer, "owner")))).isEmpty()
    }

    private fun oidcAuthority(
        issuer: String,
        subject: String,
    ): OidcUserAuthority {
        val now = Instant.now()
        val token =
            OidcIdToken
                .withTokenValue("token")
                .issuer(issuer)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build()
        return OidcUserAuthority(token)
    }
}
