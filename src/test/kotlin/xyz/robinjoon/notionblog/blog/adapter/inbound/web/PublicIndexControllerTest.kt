package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import xyz.robinjoon.notionblog.blog.application.model.PublicIndexLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicIndexUseCase
import java.net.URI

class PublicIndexControllerTest {
    private val service = mockk<GetPublicIndexUseCase>()

    @ParameterizedTest
    @ValueSource(strings = ["/sitemap.xml", "/llms.txt"])
    fun `serves the index with revalidation and answers unchanged validators with 304`(path: String) {
        every { service.get() } returns PublicIndexLookupResult.Found(PublicIndexFixtures.index())
        val mockMvc = mockMvc(PublicBlogUrls(URI("https://blog.example")))
        val response =
            mockMvc
                .perform(get(path))
                .andExpect(status().isOk)
                .andExpect(content().contentType(mediaTypes.getValue(path)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andReturn()
                .response
        val etag = requireNotNull(response.getHeader(HttpHeaders.ETAG))

        mockMvc
            .perform(get(path).header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.ETAG, etag))
            .andExpect(content().bytes(byteArrayOf()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/sitemap.xml", "/llms.txt"])
    fun `unavailable index stays 503 and uncached even for wildcard validators`(path: String) {
        every { service.get() } returns PublicIndexLookupResult.ContentUnavailable

        mockMvc(PublicBlogUrls(URI("https://blog.example")))
            .perform(get(path).header(HttpHeaders.IF_NONE_MATCH, "*"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.ETAG))
    }

    @Test
    fun `absolute index documents are unavailable without a public origin`() {
        every { service.get() } returns PublicIndexLookupResult.Found(PublicIndexFixtures.index())
        val mockMvc = mockMvc(PublicBlogUrls(null))

        listOf("/sitemap.xml", "/llms.txt").forEach { path ->
            mockMvc.perform(get(path)).andExpect(status().isServiceUnavailable)
        }
    }

    private fun mockMvc(urls: PublicBlogUrls): MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                SitemapController(service, SitemapRenderer(), urls),
                LlmsTxtController(service, LlmsTxtRenderer(PostSummaryExtractor()), urls),
            ).build()

    private companion object {
        val mediaTypes = mapOf("/sitemap.xml" to "application/xml;charset=UTF-8", "/llms.txt" to "text/plain;charset=UTF-8")
    }
}
