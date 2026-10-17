package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WebSecurityHeadersFilterTest {
    private val postLists = mockk<GetPostListUseCase>()
    private val posts = mockk<GetPublicPostUseCase>()
    private val assembler = PostPageViewAssembler(Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC))
    private val mockMvc: MockMvc = createMockMvc()

    private fun createMockMvc(): MockMvc {
        val builder = MockMvcBuilders.standaloneSetup(BlogController(postLists, posts, assembler))
        builder.addFilter<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
        return builder.build()
    }

    @Test
    fun `normal MVC response has a strict content security policy and protective headers`() {
        every { postLists.get(any()) } returns PostListLookupResult.Found(WebFixtures.listPage())

        val result = mockMvc.perform(get("/")).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertProtectiveHeaders(result)
    }

    @Test
    fun `MVC error response has the same protective headers`() {
        every { postLists.get(any()) } returns PostListLookupResult.ContentUnavailable

        val result = mockMvc.perform(get("/")).andReturn()

        assertThat(result.response.status).isEqualTo(503)
        assertProtectiveHeaders(result)
    }

    @Test
    fun `admin response allows same-origin forms and is never cached`() {
        val result = mockMvc.perform(get("/admin/posts")).andReturn()

        assertThat(result.response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(result.response.getHeader("Pragma")).isEqualTo("no-cache")
        assertThat(result.response.getHeader("Content-Security-Policy")).contains("form-action 'self'")
    }

    @Test
    fun `admin response under a context path allows same-origin forms and is never cached`() {
        val result = mockMvc.perform(get("/blog/admin/posts").contextPath("/blog")).andReturn()

        assertThat(result.response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(result.response.getHeader("Content-Security-Policy")).contains("form-action 'self'")
    }

    @Test
    fun `public response disables forms and stays cacheable`() {
        every { postLists.get(any()) } returns PostListLookupResult.ContentUnavailable

        val result = mockMvc.perform(get("/")).andReturn()

        assertThat(result.response.getHeader("Content-Security-Policy")).contains("form-action 'none'")
        assertThat(result.response.getHeader("Cache-Control")).isNull()
    }

    @Test
    fun `public path under a context path disables forms and stays cacheable`() {
        val result = mockMvc.perform(get("/admin/posts").contextPath("/admin")).andReturn()

        assertThat(result.response.getHeader("Content-Security-Policy")).contains("form-action 'none'")
        assertThat(result.response.getHeader("Cache-Control")).isNull()
    }

    private fun assertProtectiveHeaders(result: MvcResult) {
        val contentSecurityPolicy = result.response.getHeader("Content-Security-Policy")

        assertThat(contentSecurityPolicy)
            .contains(
                "default-src 'none'",
                "base-uri 'none'",
                "form-action 'none'",
                "frame-ancestors 'none'",
                "object-src 'none'",
                "script-src 'self'",
                "style-src 'self'",
                "img-src 'self' http: https:",
                "media-src 'self' http: https:",
                "font-src 'self'",
                "frame-src https://youtube.com https://www.youtube.com https://youtube-nocookie.com https://www.youtube-nocookie.com https://vimeo.com https://www.vimeo.com https://player.vimeo.com",
                "connect-src 'none'",
            ).doesNotContain("unsafe-inline", "unsafe-eval")
        assertThat(result.response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(result.response.getHeader("X-Frame-Options")).isEqualTo("DENY")
        assertThat(result.response.getHeader("Referrer-Policy")).isEqualTo("no-referrer")
        assertThat(result.response.getHeader("Permissions-Policy"))
            .isEqualTo("accelerometer=(), camera=(), geolocation=(), microphone=(), payment=(), usb=()")
    }
}
