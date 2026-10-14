package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.application.model.BlogPage
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PostPageHeadTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC)
    private val urls = PublicBlogUrls(URI("https://blog.example"))

    @Test
    fun `article head carries canonical URL summary description open graph article times and structured data`() {
        val head = head(PostPageFixtures.page(body = "  Article   body\ntext "))
        val url = "https://blog.example/posts/${PostPageFixtures.postId.value}"

        assertThat(head).contains(
            "<link rel=\"canonical\" href=\"$url\">",
            "<meta name=\"description\" content=\"Article body text\">",
            "<meta property=\"og:type\" content=\"article\">",
            "<meta property=\"og:title\" content=\"Article title\">",
            "<meta property=\"og:description\" content=\"Article body text\">",
            "<meta property=\"og:site_name\" content=\"My blog\">",
            "<meta property=\"og:locale\" content=\"ko_KR\">",
            "<meta property=\"og:url\" content=\"$url\">",
            "<meta property=\"article:published_time\" content=\"2026-10-01T09:00:00Z\">",
            "<meta property=\"article:modified_time\" content=\"2026-10-02T10:30:15Z\">",
            "<meta name=\"twitter:card\" content=\"summary\">",
            "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"BlogPosting\"",
        )
    }

    @Test
    fun `root head points canonical at the origin as a website without article times`() {
        val head = head(PostPageFixtures.page(root = true))

        assertThat(head).contains(
            "<link rel=\"canonical\" href=\"https://blog.example/\">",
            "<meta property=\"og:type\" content=\"website\">",
            "<meta property=\"og:url\" content=\"https://blog.example/\">",
            "\"@type\":\"WebSite\"",
        )
        assertThat(head).doesNotContain("article:published_time", "article:modified_time")
    }

    @Test
    fun `falls back to the site description when the post has no body text`() {
        val head = head(PostPageFixtures.page(body = ""))

        assertThat(head).contains(
            "<meta name=\"description\" content=\"Site description\">",
            "<meta property=\"og:description\" content=\"Site description\">",
        )
    }

    @Test
    fun `omits description tags when neither body text nor site description exists`() {
        val head = head(PostPageFixtures.withoutSiteDescription(PostPageFixtures.page(body = "")))

        assertThat(head).doesNotContain("name=\"description\"", "og:description")
    }

    @Test
    fun `omits absolute URLs without a configured public origin`() {
        val head = head(PostPageFixtures.page(), PublicBlogUrls(null))

        assertThat(head).doesNotContain("rel=\"canonical\"", "og:url", "\"url\"", "mainEntityOfPage")
        assertThat(head).contains("<meta property=\"og:type\" content=\"article\">", "application/ld+json")
    }

    @Test
    fun `structured data cannot break out of its script element`() {
        val head = head(PostPageFixtures.page(title = "</script><script>alert(1)</script>"))
        val jsonLd = head.substringAfter("<script type=\"application/ld+json\">").substringBefore("</script>")

        assertThat(jsonLd).contains("\\u003c/script\\u003e").doesNotContain("<", ">")
    }

    private fun head(
        page: BlogPage,
        urls: PublicBlogUrls = this.urls,
    ): String {
        val view = PostPageViewAssembler(clock, urls).assemble(page)
        val html = templateEngine().process("blog/post", Context().apply { setVariable("page", view) })
        return html.substringAfter("<head>").substringBefore("</head>")
    }

    private fun templateEngine(): SpringTemplateEngine {
        val resolver =
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                templateMode = TemplateMode.HTML
                characterEncoding = "UTF-8"
                isCacheable = false
            }
        return SpringTemplateEngine().apply { setTemplateResolver(resolver) }
    }
}
