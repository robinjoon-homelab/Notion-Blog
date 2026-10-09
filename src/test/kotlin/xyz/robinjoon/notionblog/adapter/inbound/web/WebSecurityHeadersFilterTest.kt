package xyz.robinjoon.notionblog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import xyz.robinjoon.notionblog.adapter.inbound.web.view.PostPageView
import xyz.robinjoon.notionblog.application.model.BlogPage
import xyz.robinjoon.notionblog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.application.port.input.GetBlogPageUseCase

class WebSecurityHeadersFilterTest {
    private val blogPages = mockk<GetBlogPageUseCase>()
    private val assembler = mockk<PostPageViewAssembler>()
    private val mockMvc: MockMvc = createMockMvc()

    private fun createMockMvc(): MockMvc {
        val builder = MockMvcBuilders.standaloneSetup(BlogController(blogPages, assembler))
        builder.addFilter<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
        return builder.build()
    }

    @Test
    fun `normal MVC response has a strict content security policy and protective headers`() {
        val page = mockk<BlogPage>()
        val view = mockk<PostPageView>()
        every { blogPages.getRoot() } returns BlogPageLookupResult.Found(page)
        every { assembler.assemble(page) } returns view

        val result = mockMvc.perform(get("/")).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertProtectiveHeaders(result)
    }

    @Test
    fun `MVC error response has the same protective headers`() {
        every { blogPages.getRoot() } returns BlogPageLookupResult.ContentUnavailable

        val result = mockMvc.perform(get("/")).andReturn()

        assertThat(result.response.status).isEqualTo(503)
        assertProtectiveHeaders(result)
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
