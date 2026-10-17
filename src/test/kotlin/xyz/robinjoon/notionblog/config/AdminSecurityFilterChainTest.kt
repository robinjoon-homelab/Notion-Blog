package xyz.robinjoon.notionblog.config

import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

// 관리 경로의 인증·권한·CSRF를 실제 필터 체인으로 확인한다. 로그인 화면은 컨트롤러 테스트가 다루므로 여기서는 보안 필터의 결과만 본다.
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
        "blog.admin.username=admin",
        "blog.admin.password=fixture-password",
    ],
)
class AdminSecurityFilterChainTest(
    context: WebApplicationContext,
) {
    private val mockMvc: MockMvc =
        MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

    private fun signIn(): MockHttpSession {
        val result =
            mockMvc
                .perform(formLogin("/admin/login").user("admin").password("fixture-password"))
                .andExpect(status().isFound)
                .andExpect(redirectedUrl("/admin/posts"))
                .andReturn()
        return requireNotNull(result.request.getSession(false)) as MockHttpSession
    }

    @Test
    fun `public pages need no login`() {
        mockMvc.perform(get("/probe")).andExpect(status().isOk)
    }

    @Test
    fun `anonymous admin access is sent to the login form`() {
        mockMvc.perform(get("/admin/probe")).andExpect(status().isFound).andExpect(redirectedUrl("/admin/login"))
    }

    @Test
    fun `correct credentials open the admin session`() {
        mockMvc.perform(get("/admin/probe").session(signIn())).andExpect(status().isOk)
    }

    @Test
    fun `wrong password or unknown username gets the same generic failure`() {
        mockMvc
            .perform(formLogin("/admin/login").user("admin").password("wrong"))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/admin/login?error"))
        mockMvc
            .perform(formLogin("/admin/login").user("someone").password("fixture-password"))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/admin/login?error"))
    }

    @Test
    fun `login submission needs a CSRF token`() {
        mockMvc
            .perform(post("/admin/login").param("username", "admin").param("password", "fixture-password"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `signed in user without the admin authority is forbidden`() {
        mockMvc.perform(get("/admin/probe").with(user("guest").authorities())).andExpect(status().isForbidden)
    }

    @Test
    fun `state changing admin requests need a CSRF token`() {
        val session = signIn()

        mockMvc.perform(post("/admin/probe").session(session)).andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/probe").session(session).with(csrf())).andExpect(status().isOk)
    }

    @Test
    fun `logout is a CSRF protected POST that ends the session and returns home`() {
        val session = signIn()

        mockMvc.perform(post("/admin/logout").session(session)).andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/logout").session(session).with(csrf())).andExpect(status().isFound).andExpect(redirectedUrl("/"))
        mockMvc.perform(get("/admin/probe").session(session)).andExpect(status().isFound).andExpect(redirectedUrl("/admin/login"))
    }
}
