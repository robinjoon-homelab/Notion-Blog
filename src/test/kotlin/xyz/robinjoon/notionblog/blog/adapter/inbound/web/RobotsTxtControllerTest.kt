package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.URI

class RobotsTxtControllerTest {
    @Test
    fun `allows every crawler and points at the sitemap of the configured origin`() {
        val mockMvc = MockMvcBuilders.standaloneSetup(RobotsTxtController(PublicBlogUrls(URI("https://blog.example")))).build()

        mockMvc
            .perform(get("/robots.txt"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/plain;charset=UTF-8"))
            .andExpect(content().string("User-agent: *\nAllow: /\n\nSitemap: https://blog.example/sitemap.xml\n"))
    }

    @Test
    fun `still allows every crawler without a sitemap line when no public origin is configured`() {
        val mockMvc = MockMvcBuilders.standaloneSetup(RobotsTxtController(PublicBlogUrls(null))).build()

        mockMvc
            .perform(get("/robots.txt"))
            .andExpect(status().isOk)
            .andExpect(content().string("User-agent: *\nAllow: /\n"))
    }
}
