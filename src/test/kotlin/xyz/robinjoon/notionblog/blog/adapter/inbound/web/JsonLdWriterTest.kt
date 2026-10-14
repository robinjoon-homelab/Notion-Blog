package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI

class JsonLdWriterTest {
    private val writer = JsonLdWriter()
    private val urls = PublicBlogUrls(URI("https://blog.example"))

    @Test
    fun `escapes markup characters so a title cannot close the script element`() {
        val json = writer.write(PostPageFixtures.page(title = "</script><b>&amp;"), "desc", urls)

        assertThat(json).doesNotContain("<", ">", "&")
        assertThat(parse(json).required("headline").stringValue()).isEqualTo("</script><b>&amp;")
    }

    @Test
    fun `describes an article as a blog posting that belongs to the website without an author`() {
        val node = parse(writer.write(PostPageFixtures.page(), "Article summary", urls))
        val url = "https://blog.example/posts/${PostPageFixtures.postId.value}"

        assertThat(node.required("@context").stringValue()).isEqualTo("https://schema.org")
        assertThat(node.required("@type").stringValue()).isEqualTo("BlogPosting")
        assertThat(node.required("headline").stringValue()).isEqualTo("Article title")
        assertThat(node.required("description").stringValue()).isEqualTo("Article summary")
        assertThat(node.required("datePublished").stringValue()).isEqualTo("2026-10-01T09:00:00Z")
        assertThat(node.required("dateModified").stringValue()).isEqualTo("2026-10-02T10:30:15Z")
        assertThat(node.required("inLanguage").stringValue()).isEqualTo("ko-KR")
        assertThat(node.required("url").stringValue()).isEqualTo(url)
        assertThat(node.required("mainEntityOfPage").stringValue()).isEqualTo(url)
        assertThat(node.required("isPartOf").required("@type").stringValue()).isEqualTo("WebSite")
        assertThat(node.required("isPartOf").required("name").stringValue()).isEqualTo("My blog")
        assertThat(node.required("isPartOf").required("url").stringValue()).isEqualTo("https://blog.example/")
        assertThat(node.has("author")).isFalse()
    }

    @Test
    fun `describes the root as the website at the origin`() {
        val node = parse(writer.write(PostPageFixtures.page(root = true), "Root summary", urls))

        assertThat(node.required("@type").stringValue()).isEqualTo("WebSite")
        assertThat(node.required("name").stringValue()).isEqualTo("My blog")
        assertThat(node.required("description").stringValue()).isEqualTo("Site description")
        assertThat(node.required("inLanguage").stringValue()).isEqualTo("ko-KR")
        assertThat(node.required("url").stringValue()).isEqualTo("https://blog.example/")
        assertThat(node.has("headline")).isFalse()
    }

    @Test
    fun `omits unknown publication time absolute URLs and missing descriptions`() {
        val node = parse(writer.write(PostPageFixtures.page(firstPublishedAt = null), null, PublicBlogUrls(null)))

        assertThat(node.has("datePublished")).isFalse()
        assertThat(node.has("description")).isFalse()
        assertThat(node.has("url")).isFalse()
        assertThat(node.has("mainEntityOfPage")).isFalse()
        assertThat(node.required("isPartOf").has("url")).isFalse()
        assertThat(node.required("dateModified").stringValue()).isEqualTo("2026-10-02T10:30:15Z")
    }

    private fun parse(json: String): JsonNode = JsonMapper.builder().build().readTree(json)
}
