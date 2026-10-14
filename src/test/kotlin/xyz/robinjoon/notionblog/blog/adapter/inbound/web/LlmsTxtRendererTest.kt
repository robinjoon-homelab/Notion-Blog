package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class LlmsTxtRendererTest {
    private val renderer = LlmsTxtRenderer(PostSummaryExtractor())
    private val urls = PublicBlogUrls(URI("https://blog.example"))

    @Test
    fun `describes the site and links every public page with its summary`() {
        val text = render(PublicIndexFixtures.index())

        assertThat(text).isEqualTo(
            """
            # My blog

            > Notes about building things

            ## Posts

            - [Home](https://blog.example/): Welcome text
            - [First article](https://blog.example/posts/${PublicIndexFixtures.articleId.value}): Article body

            ## Optional

            - [RSS](https://blog.example/feed.xml): 최근 글 구독 피드
            - [Sitemap](https://blog.example/sitemap.xml): 전체 공개 URL 목록

            """.trimIndent(),
        )
    }

    @Test
    fun `escapes link text and keeps every entry on one line`() {
        val text = render(PublicIndexFixtures.index(entries = listOf(PublicIndexFixtures.article("a [b]\n\\c", "line one\nline two"))))

        assertThat(
            text,
        ).contains("- [a \\[b\\] \\\\c](https://blog.example/posts/${PublicIndexFixtures.articleId.value}): line one line two\n")
    }

    @Test
    fun `omits the description quote and empty summaries`() {
        val metadata = PublicIndexFixtures.metadata.copy(defaultDescription = null)
        val text = render(PublicIndexFixtures.index(metadata, listOf(PublicIndexFixtures.root(body = ""))))

        assertThat(text).startsWith("# My blog\n\n## Posts\n\n- [Home](https://blog.example/)\n\n## Optional")
        assertThat(text).doesNotContain(">")
    }

    private fun render(index: xyz.robinjoon.notionblog.blog.application.model.PublicIndex): String =
        renderer.render(index, urls).toString(Charsets.UTF_8)
}
