package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

class SitemapRendererTest {
    private val urls = PublicBlogUrls(URI("https://blog.example"))

    @Test
    fun `lists the root at the origin first and articles at their UUID path with second precision modification times`() {
        val document = parse(SitemapRenderer().render(PublicIndexFixtures.index(), urls))

        val root = document.documentElement
        assertThat(root.namespaceURI).isEqualTo(SITEMAP_NAMESPACE)
        assertThat(root.localName).isEqualTo("urlset")
        val entries = root.getElementsByTagNameNS(SITEMAP_NAMESPACE, "url")
        assertThat(entries.length).isEqualTo(2)
        assertThat(entries.element(0).value("loc")).isEqualTo("https://blog.example/")
        assertThat(entries.element(0).value("lastmod")).isEqualTo("2026-10-03T04:05:06Z")
        assertThat(entries.element(1).value("loc"))
            .isEqualTo("https://blog.example/posts/${PublicIndexFixtures.articleId.value}")
        assertThat(entries.element(1).value("lastmod")).isEqualTo("2026-10-04T00:00:00Z")
    }

    @Test
    fun `renders an empty url set for an initialized blog without public posts`() {
        val document = parse(SitemapRenderer().render(PublicIndexFixtures.index(entries = emptyList()), urls))

        assertThat(document.documentElement.getElementsByTagNameNS(SITEMAP_NAMESPACE, "url").length).isZero()
    }

    private fun parse(bytes: ByteArray): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder()
            .parse(bytes.inputStream())

    private fun NodeList.element(index: Int): Element = checkNotNull(item(index)) as Element

    private fun Element.value(name: String): String = getElementsByTagNameNS(SITEMAP_NAMESPACE, name).item(0).textContent

    private companion object {
        const val SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9"
    }
}
