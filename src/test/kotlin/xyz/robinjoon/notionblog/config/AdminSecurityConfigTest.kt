package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class AdminSecurityConfigTest {
    private val configured = AdminSecurityProperties(username = "admin", password = "fixture-password")
    private val encoder = AdminSecurityConfig(configured).adminPasswordEncoder()

    @Test
    fun `the operator holds the admin authority with an encoded password`() {
        val user = AdminSecurityConfig(configured).adminUserDetailsService(encoder).loadUserByUsername("admin")

        assertThat(user.password).isNotEqualTo("fixture-password")
        assertThat(encoder.matches("fixture-password", user.password)).isTrue()
        assertThat(user.authorities).containsExactly(SimpleGrantedAuthority(ADMIN_ROLE))
    }

    @Test
    fun `no account exists while either credential is missing`() {
        val incomplete =
            listOf(
                AdminSecurityProperties(),
                AdminSecurityProperties(username = "admin", password = " "),
                AdminSecurityProperties(username = " ", password = "fixture-password"),
            )

        incomplete.forEach { properties ->
            val users = AdminSecurityConfig(properties).adminUserDetailsService(encoder)

            assertThatThrownBy { users.loadUserByUsername("admin") }.isInstanceOf(UsernameNotFoundException::class.java)
        }
    }

    @Test
    fun `a bcrypt hash is stored as given`() {
        val hash = BCryptPasswordEncoder().encode("fixture-password").let { "{bcrypt}$it" }
        val properties = AdminSecurityProperties(username = "admin", password = hash)

        val user = AdminSecurityConfig(properties).adminUserDetailsService(encoder).loadUserByUsername("admin")

        assertThat(user.password).isEqualTo(hash)
        assertThat(encoder.matches("fixture-password", user.password)).isTrue()
    }
}
