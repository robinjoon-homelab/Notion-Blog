package xyz.robinjoon.notionblog.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.context.WebApplicationContext

// 인증 설정이 없으면 누구도 관리 경로에 들어갈 수 없고 공개 읽기만 동작한다.
@SpringJUnitWebConfig(AdminSecurityProbeConfig::class)
class AdminSecurityClosedChainTest
    @Autowired
    constructor(
        context: WebApplicationContext,
    ) {
        private val mockMvc = securedMockMvc(context)

        @ParameterizedTest
        @ValueSource(strings = ["/admin/probe", "/docs/probe", "/admin/login"])
        fun `every protected path is forbidden without credentials`(path: String) {
            mockMvc.perform(get(path)).andExpect(status().isForbidden)
        }

        // MockMvc는 오류 디스패치를 하지 않으므로 reason으로 sendError와 setStatus를 구분한다.
        @Test
        fun `anonymous rejection reaches the error page`() {
            mockMvc
                .perform(get("/admin/probe"))
                .andExpect(status().isForbidden)
                .andExpect(status().reason("Forbidden"))
        }

        @Test
        fun `public pages stay readable`() {
            mockMvc.perform(get("/probe")).andExpect(status().isOk)
        }

        @Test
        fun `admin is closed even to a signed in user with the admin role`() {
            val admin = user("admin").authorities(SimpleGrantedAuthority(ADMIN_ROLE))

            mockMvc.perform(get("/admin/probe").with(admin)).andExpect(status().isForbidden)
            mockMvc.perform(post("/admin/probe").with(csrf())).andExpect(status().isForbidden)
        }

        @Test
        fun `form login is forbidden without credentials`() {
            mockMvc
                .perform(formLogin("/admin/login").user("admin").password("fixture-password"))
                .andExpect(status().isForbidden)
        }
    }

// BCrypt가 해시할 수 없는 비밀번호는 컨텍스트 시작을 막지 않고 관리 체인을 닫아야 한다.
@SpringJUnitWebConfig(AdminSecurityProbeConfig::class)
@TestPropertySource(properties = ["app.admin.username=admin", "app.admin.password=$OVER_LONG_PASSWORD"])
class AdminSecurityOverLongPasswordChainTest
    @Autowired
    constructor(
        context: WebApplicationContext,
    ) {
        private val mockMvc = securedMockMvc(context)

        @ParameterizedTest
        @ValueSource(strings = ["/admin/probe", "/docs/probe", "/admin/login"])
        fun `every protected path is forbidden`(path: String) {
            mockMvc.perform(get(path)).andExpect(status().isForbidden)
        }

        @Test
        fun `public pages stay readable`() {
            mockMvc.perform(get("/probe")).andExpect(status().isOk)
        }
    }

private const val OVER_LONG_PASSWORD = "0123456789012345678901234567890123456789012345678901234567890123456789012"
