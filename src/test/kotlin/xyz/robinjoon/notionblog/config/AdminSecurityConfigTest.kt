package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UsernameNotFoundException

class AdminSecurityConfigTest {
    private val config = AdminSecurityConfig()
    private val encoder = config.adminPasswordEncoder()
    private val configured = AdminSecurityProperties(username = "admin", password = "fixture-password")

    @Test
    fun `the operator holds the admin authority with an encoded password`() {
        val user = config.adminUserDetailsService(configured, encoder).loadUserByUsername("admin")

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
            val users = config.adminUserDetailsService(properties, encoder)

            assertThatThrownBy { users.loadUserByUsername("admin") }.isInstanceOf(UsernameNotFoundException::class.java)
        }
    }
}
