package xyz.robinjoon.notionblog.config

import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.ResponseEntity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

// 관리 경로의 인증·권한·CSRF를 실제 필터 체인으로 확인한다. 발급자 탐색은 첫 로그인까지 일어나지 않으므로 네트워크가 필요 없다.
@Configuration
@EnableWebMvc
@EnableWebSecurity
@Import(AdminSecurityConfig::class)
internal class SecurityProbeConfig {
    @Bean
    fun probe() = SecurityProbeController()
}

@RestController
internal class SecurityProbeController {
    @GetMapping("/admin/probe", "/probe")
    fun read(): ResponseEntity<String> = ResponseEntity.ok("ok")

    @PostMapping("/admin/probe")
    fun write(): ResponseEntity<String> = ResponseEntity.ok("done")
}

@SpringJUnitWebConfig(SecurityProbeConfig::class)
@TestPropertySource(
    properties = [
        "blog.admin.oidc.issuer-uri=https://id.example",
        "blog.admin.oidc.client-id=client",
        "blog.admin.oidc.client-secret=secret",
        "blog.admin.oidc.allowed-subject=owner",
    ],
)
class AdminSecurityFilterChainTest(
    context: WebApplicationContext,
) {
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .webAppContextSetup(
                context,
            ).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
            .build()
    private val admin = oidcLogin().authorities(SimpleGrantedAuthority(ADMIN_ROLE))

    @Test
    fun `public pages need no login`() {
        mockMvc.perform(get("/probe")).andExpect(status().isOk)
    }

    @Test
    fun `anonymous admin access is sent to the OIDC login`() {
        mockMvc
            .perform(
                get("/admin/probe"),
            ).andExpect(status().isFound)
            .andExpect(redirectedUrl("/oauth2/authorization/admin"))
    }

    @Test
    fun `signed in user outside the allowlist is forbidden`() {
        mockMvc.perform(get("/admin/probe").with(oidcLogin())).andExpect(status().isForbidden)
    }

    @Test
    fun `allowed administrator can read`() {
        mockMvc.perform(get("/admin/probe").with(admin)).andExpect(status().isOk)
    }

    @Test
    fun `state changing admin requests need a CSRF token`() {
        mockMvc.perform(post("/admin/probe").with(admin)).andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/probe").with(admin).with(csrf())).andExpect(status().isOk)
    }

    @Test
    fun `logout is a CSRF protected POST that returns home`() {
        mockMvc.perform(post("/admin/logout").with(admin)).andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/logout").with(admin).with(csrf())).andExpect(status().isFound).andExpect(redirectedUrl("/"))
    }
}
