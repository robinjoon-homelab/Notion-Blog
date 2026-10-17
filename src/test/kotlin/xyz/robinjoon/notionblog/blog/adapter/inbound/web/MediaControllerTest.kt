package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import xyz.robinjoon.notionblog.blog.application.model.MediaContent
import xyz.robinjoon.notionblog.blog.application.model.MediaLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetMediaUseCase
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey

class MediaControllerTest {
    private val media = mockk<GetMediaUseCase>()
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(MediaController(media)).build()
    private val key = MediaKey("a".repeat(64))
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun `public image is served inline with a validator`() {
        every { media.getPublic(key) } returns MediaLookupResult.Found(MediaContent(key, "image/png", bytes))

        mockMvc
            .perform(get("/media/${key.value}"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(bytes))
            .andExpect(header().string(HttpHeaders.ETAG, "\"${key.value}\""))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    @Test
    fun `directly opened files carry a policy that blocks scripts and framing`() {
        every { media.getPublic(key) } returns MediaLookupResult.Found(MediaContent(key, "image/svg+xml", bytes))
        val filtered =
            MockMvcBuilders
                .standaloneSetup(
                    MediaController(media),
                ).addFilters<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
                .build()

        val csp =
            filtered
                .perform(get("/media/${key.value}"))
                .andReturn()
                .response
                .getHeader("Content-Security-Policy")

        assertThat(csp).contains("default-src 'none'", "script-src 'self'", "frame-ancestors 'none'").doesNotContain("unsafe-inline")
    }

    @Test
    fun `unknown file types download as attachments`() {
        every { media.getPublic(key) } returns MediaLookupResult.Found(MediaContent(key, "text/html", bytes))

        mockMvc
            .perform(get("/media/${key.value}"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment"))
    }

    @Test
    fun `matching validator returns 304 without a body`() {
        every { media.getPublic(key) } returns MediaLookupResult.Found(MediaContent(key, "image/png", bytes))

        mockMvc
            .perform(get("/media/${key.value}").header(HttpHeaders.IF_NONE_MATCH, "\"other\", \"${key.value}\""))
            .andExpect(status().isNotModified)
            .andExpect(content().bytes(ByteArray(0)))
    }

    @Test
    fun `malformed key and missing file are not found`() {
        every { media.getPublic(key) } returns MediaLookupResult.NotFound

        mockMvc.perform(get("/media/not-a-key")).andExpect(status().isNotFound)
        mockMvc.perform(get("/media/${key.value}")).andExpect(status().isNotFound)
    }

    @Test
    fun `preview file is private and never cached`() {
        every { media.getForPreview(key) } returns MediaLookupResult.Found(MediaContent(key, "image/png", bytes))

        mockMvc
            .perform(get("/admin/media/${key.value}"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.ETAG))
    }
}
