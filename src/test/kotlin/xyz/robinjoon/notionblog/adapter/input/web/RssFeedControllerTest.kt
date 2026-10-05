package xyz.robinjoon.notionblog.adapter.input.web

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import xyz.robinjoon.notionblog.application.model.PostFeed
import xyz.robinjoon.notionblog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.application.model.PostFeedLookupResult
import xyz.robinjoon.notionblog.application.service.GetPostFeedService
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class RssFeedControllerTest {
    private val service = mockk<GetPostFeedService>()
    private val renderer = RssFeedRenderer(RssSummaryExtractor())
    private val urls = PublicBlogUrls(URI("https://trusted.example"))
    private val mockMvc = MockMvcBuilders.standaloneSetup(RssFeedController(service, renderer, urls)).build()

    @Test
    fun `available feed returns UTF-8 XML with SHA-256 of transmitted bytes and revalidation policy`() {
        every { service.get() } returns PostFeedLookupResult.Found(feed())

        val response = mockMvc.perform(get("/feed.xml"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/rss+xml"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
            .andReturn().response

        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.contentAsByteArray))
        assertThat(response.characterEncoding).isEqualTo("UTF-8")
        assertThat(response.getHeader(HttpHeaders.ETAG)).isEqualTo("\"$digest\"")
        assertThat(response.contentAsByteArray).isEqualTo(renderer.render(feed(), urls))
    }

    @ParameterizedTest
    @ValueSource(strings = ["exact", "weak", "list", "wildcard"])
    fun `matching validators return 304 with empty body ETag and no-cache`(validator: String) {
        every { service.get() } returns PostFeedLookupResult.Found(feed())
        val etag = requireNotNull(mockMvc.perform(get("/feed.xml")).andReturn().response.getHeader(HttpHeaders.ETAG))
        val ifNoneMatch = when (validator) {
            "weak" -> "W/$etag"
            "list" -> "\"different\", W/$etag, \"another\""
            "wildcard" -> "*"
            else -> etag
        }

        val response = mockMvc.perform(get("/feed.xml").header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch))
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.ETAG, etag))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andReturn().response

        assertThat(response.contentAsByteArray).isEmpty()
        verify(exactly = 2) { service.get() }
    }

    @Test
    fun `normal empty feed exists and supports wildcard revalidation`() {
        every { service.get() } returns PostFeedLookupResult.Found(feed().copy(entries = emptyList()))

        mockMvc.perform(get("/feed.xml"))
            .andExpect(status().isOk)
        val response = mockMvc.perform(get("/feed.xml").header(HttpHeaders.IF_NONE_MATCH, "*"))
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andReturn().response

        assertThat(response.contentAsByteArray).isEmpty()
    }

    @Test
    fun `missing public base URL returns 503 no-store without querying content even with wildcard`() {
        val unconfigured = MockMvcBuilders.standaloneSetup(RssFeedController(service, renderer, PublicBlogUrls(null))).build()

        val response = unconfigured.perform(get("/feed.xml").header(HttpHeaders.IF_NONE_MATCH, "*"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.ETAG))
            .andReturn().response

        assertThat(response.contentAsByteArray).isEmpty()
        verify(exactly = 0) { service.get() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["old-etag", "wildcard"])
    fun `unavailable content cannot be converted to 304 by a previous or wildcard validator`(validator: String) {
        every { service.get() } returns PostFeedLookupResult.Found(feed())
        val etag = requireNotNull(mockMvc.perform(get("/feed.xml")).andReturn().response.getHeader(HttpHeaders.ETAG))
        every { service.get() } returns PostFeedLookupResult.ContentUnavailable

        val response = mockMvc.perform(get("/feed.xml").header(HttpHeaders.IF_NONE_MATCH, if (validator == "wildcard") "*" else etag))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.ETAG))
            .andReturn().response

        assertThat(response.contentAsByteArray).isEmpty()
    }

    @Test
    fun `changed representation returns 200 and a new validator after content is re-evaluated`() {
        every { service.get() } returns PostFeedLookupResult.Found(feed())
        val etag = requireNotNull(mockMvc.perform(get("/feed.xml")).andReturn().response.getHeader(HttpHeaders.ETAG))
        every { service.get() } returns PostFeedLookupResult.Found(feed().copy(metadata = metadata().copy(siteName = "New name")))

        val response = mockMvc.perform(get("/feed.xml").header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isOk)
            .andReturn().response

        assertThat(response.getHeader(HttpHeaders.ETAG)).isNotEqualTo(etag)
        assertThat(response.contentAsString).contains("New name")
    }

    @Test
    fun `content changes beyond the summary retain the same representation validator`() {
        every { service.get() } returns PostFeedLookupResult.Found(feed("a".repeat(300) + "old"))
        val etag = requireNotNull(mockMvc.perform(get("/feed.xml")).andReturn().response.getHeader(HttpHeaders.ETAG))
        every { service.get() } returns PostFeedLookupResult.Found(feed("a".repeat(300) + "new"))

        mockMvc.perform(get("/feed.xml").header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.ETAG, etag))
    }

    @Test
    fun `request host and forwarded headers never influence published feed URLs`() {
        every { service.get() } returns PostFeedLookupResult.Found(feed())

        val response = mockMvc.perform(
            get("/feed.xml")
                .header(HttpHeaders.HOST, "attacker.example")
                .header("Forwarded", "host=attacker.example;proto=http")
                .header("X-Forwarded-Host", "attacker.example")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Prefix", "/attacker"),
        )
            .andExpect(status().isOk)
            .andReturn().response

        assertThat(response.contentAsString).contains("https://trusted.example/feed.xml", "https://trusted.example/posts/")
        assertThat(response.contentAsString).doesNotContain("attacker.example", "/attacker")
    }

    @Test
    fun `If-Modified-Since alone does not replace representation validation`() {
        every { service.get() } returns PostFeedLookupResult.Found(feed())

        mockMvc.perform(get("/feed.xml").header(HttpHeaders.IF_MODIFIED_SINCE, "Mon, 05 Oct 2099 00:00:00 GMT"))
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
    }

    private fun metadata() = SiteMetadata("Blog", "Description", "ko-KR", null)

    private fun feed(summary: String = "Summary 😀") = PostFeed(
        metadata(),
        listOf(
            PostFeedEntry(
                Post(
                    PostId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    "Title",
                    BlockTree(listOf(BlockNode(BlockId("text"), TextBlockContent.Paragraph(listOf(InlineContent.Text(summary)))))),
                ),
                Instant.parse("2026-10-05T03:04:05Z"),
            ),
        ),
    )
}
