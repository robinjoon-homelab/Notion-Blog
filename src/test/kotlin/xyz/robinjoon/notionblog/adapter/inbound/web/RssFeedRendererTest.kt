package xyz.robinjoon.notionblog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import xyz.robinjoon.notionblog.application.model.PostFeed
import xyz.robinjoon.notionblog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class RssFeedRendererTest {
    private val renderer = RssFeedRenderer(RssSummaryExtractor())
    private val urls = PublicBlogUrls(URI("https://blog.example:8443"))
    private val publishedAt = Instant.parse("2026-10-05T03:04:05Z")

    @Test
    fun `RSS channel and items have required metadata absolute links stable GUID and GMT date`() {
        val entry = entry("First 글", "Summary 😀")
        val feed = PostFeed(metadata(), listOf(entry, entry("Second", "Second summary", 2)))
        val bytes = renderer.render(feed, urls)
        val document = parse(bytes)
        val channel = document.getElementsByTagNameNS(null, "channel").element()
        val items = document.getElementsByTagNameNS(null, "item")
        val item = items.element()
        val selfLink = document.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link").element()

        assertThat(String(bytes, Charsets.UTF_8)).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(document.documentElement.namespaceURI).isNull()
        assertThat(document.documentElement.tagName).isEqualTo("rss")
        assertThat(document.documentElement.getAttribute("version")).isEqualTo("2.0")
        assertThat(channel.value("title")).isEqualTo("My Blog")
        assertThat(channel.value("description")).isEqualTo("Blog description")
        assertThat(channel.value("language")).isEqualTo("ko-KR")
        assertThat(channel.value("link")).isEqualTo("https://blog.example:8443/")
        assertThat(selfLink.getAttribute("rel")).isEqualTo("self")
        assertThat(selfLink.getAttribute("type")).isEqualTo("application/rss+xml")
        assertThat(selfLink.getAttribute("href")).isEqualTo("https://blog.example:8443/feed.xml")
        assertThat(items.length).isEqualTo(2)
        assertThat(item.value("title")).isEqualTo("First 글")
        assertThat(items.element(1).value("title")).isEqualTo("Second")
        assertThat(item.value("description")).isEqualTo("Summary 😀")
        assertThat(item.value("link")).isEqualTo("https://blog.example:8443/posts/${entry.post.id.value}")
        assertThat(item.value("guid")).isEqualTo("urn:uuid:${entry.post.id.value}")
        assertThat(item.getElementsByTagName("guid").element().getAttribute("isPermaLink")).isEqualTo("false")
        assertThat(item.value("pubDate")).endsWith(" GMT")
        assertThat(ZonedDateTime.parse(item.value("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).isEqualTo(publishedAt)
        assertThat(document.getElementsByTagName("lastBuildDate").length).isZero()
        assertThat(renderer.render(feed, urls)).isEqualTo(bytes)
    }

    @Test
    fun `empty feed is valid and absent description falls back to site name`() {
        val document = parse(renderer.render(PostFeed(metadata().copy(defaultDescription = null), emptyList()), urls))

        assertThat(document.getElementsByTagNameNS(null, "item").length).isZero()
        assertThat(document.getElementsByTagName("channel").element().value("description")).isEqualTo("My Blog")
    }

    @Test
    fun `domain and content changes preserve GUID and first publication date`() {
        val original = entry("Original", "Original body")
        val changed = original.copy(post = original.post.copy(title = "Changed title"))
        val before = parse(renderer.render(PostFeed(metadata(), listOf(original)), urls))
        val after = parse(renderer.render(PostFeed(metadata(), listOf(changed)), PublicBlogUrls(URI("https://moved.example"))))

        assertThat(
            after.getElementsByTagName("guid").item(0).textContent,
        ).isEqualTo(before.getElementsByTagName("guid").item(0).textContent)
        assertThat(
            after.getElementsByTagName("pubDate").item(0).textContent,
        ).isEqualTo(before.getElementsByTagName("pubDate").item(0).textContent)
        assertThat(after.getElementsByTagName("link").item(0).textContent).isEqualTo("https://moved.example/")
    }

    @Test
    fun `item description is HTML escaped inside XML while other text keeps its literal meaning`() {
        val malicious = "<script>alert(\"x\")</script> & ]]>"
        val metadata = metadata().copy(siteName = malicious, defaultDescription = malicious)
        val bytes = renderer.render(PostFeed(metadata, listOf(entry(malicious, malicious))), urls)
        val document = parse(bytes)
        val channel = document.getElementsByTagName("channel").element()
        val item = document.getElementsByTagName("item").element()

        assertThat(channel.value("title")).isEqualTo(malicious)
        assertThat(channel.value("description")).isEqualTo(malicious)
        assertThat(item.value("title")).isEqualTo(malicious)
        assertThat(item.value("description")).isEqualTo("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; ]]&gt;")
        assertThat(String(bytes, Charsets.UTF_8)).contains("&amp;lt;script&amp;gt;")
        assertThat(document.getElementsByTagName("script").length).isZero()
    }

    @Test
    fun `all external text obeys the complete XML 1 point 0 character allowlist`() {
        val forbidden = "\u0000\u0001\u000B\u000C\u001F\uFFFE\uFFFF\uD800x\uDC00"
        val allowed = "\t\n\r \u0085\uFDD0😀👩‍💻"
        val metadata =
            metadata().copy(
                siteName = "Site$forbidden$allowed",
                defaultDescription = "Description$forbidden$allowed",
                languageTag = "ko\uFFFF-KR",
            )
        val document = parse(renderer.render(PostFeed(metadata, listOf(entry("Title$forbidden$allowed", "body"))), urls))
        val channel = document.getElementsByTagName("channel").element()
        val item = document.getElementsByTagName("item").element()

        // XML parsers normalize literal CR to LF; legal noncharacters and emoji still survive.
        assertThat(channel.value("title")).isEqualTo("Sitex" + allowed.replace('\r', '\n'))
        assertThat(channel.value("description")).isEqualTo("Descriptionx" + allowed.replace('\r', '\n'))
        assertThat(channel.value("language")).isEqualTo("ko-KR")
        assertThat(item.value("title")).isEqualTo("Titlex" + allowed.replace('\r', '\n'))
    }

    @Test
    fun `forbidden only site title description and post title use safe readable fallbacks`() {
        val invalid = "\u0000\uFFFE\uFFFF\uD800"
        val document =
            parse(
                renderer.render(
                    PostFeed(metadata().copy(siteName = invalid, defaultDescription = invalid), listOf(entry(invalid, invalid))),
                    urls,
                ),
            )
        val channel = document.getElementsByTagName("channel").element()
        val item = document.getElementsByTagName("item").element()

        assertThat(channel.value("title")).isEqualTo("Blog")
        assertThat(channel.value("description")).isEqualTo("Blog")
        assertThat(item.value("title")).isEqualTo("제목 없는 글")
        assertThat(item.value("description")).isEqualTo("제목 없는 글")
    }

    private fun metadata() = SiteMetadata("My Blog", "Blog description", "ko-KR", null)

    private fun entry(
        title: String,
        summary: String,
        id: Int = 1,
    ) = PostFeedEntry(
        Post(
            PostId(UUID.fromString("00000000-0000-0000-0000-${id.toString().padStart(12, '0')}")),
            title,
            BlockTree(listOf(BlockNode(BlockId("text"), TextBlockContent.Paragraph(listOf(InlineContent.Text(summary)))))),
        ),
        publishedAt,
    )

    private fun parse(bytes: ByteArray): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder()
            .parse(bytes.inputStream())

    private fun Element.value(name: String): String = getElementsByTagNameNS(null, name).item(0).textContent

    private fun NodeList.element(index: Int = 0): Element = checkNotNull(item(index)) as Element
}
