package xyz.robinjoon.notionblog.config

import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

// 인증 설정이 없으면 누구도 관리 경로에 들어갈 수 없고 공개 읽기만 동작한다.
@SpringJUnitWebConfig(SecurityProbeConfig::class)
class AdminSecurityClosedChainTest(
    context: WebApplicationContext,
) {
    private val mockMvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

    @Test
    fun `public pages stay readable`() {
        mockMvc.perform(get("/probe")).andExpect(status().isOk)
    }

    @Test
    fun `admin is closed to anonymous and even to a signed in user with the admin role`() {
        mockMvc.perform(get("/admin/probe")).andExpect(status().isForbidden)
        mockMvc
            .perform(get("/admin/probe").with(oidcLogin().authorities(SimpleGrantedAuthority(ADMIN_ROLE))))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/probe").with(csrf())).andExpect(status().isForbidden)
    }
}
