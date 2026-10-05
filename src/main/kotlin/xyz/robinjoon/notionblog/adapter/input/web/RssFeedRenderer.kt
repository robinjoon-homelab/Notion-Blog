package xyz.robinjoon.notionblog.adapter.input.web

import org.springframework.web.util.HtmlUtils
import xyz.robinjoon.notionblog.application.model.PostFeed
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

class RssFeedRenderer(private val summaryExtractor: RssSummaryExtractor) {
    fun render(feed: PostFeed, urls: PublicBlogUrls): ByteArray {
        val rootUrl = requireNotNull(urls.rootUrl) { "A public blog origin is required" }
        val feedUrl = requireNotNull(urls.feedUrl) { "A public blog origin is required" }
        val output = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output, Charsets.UTF_8.name())
        try {
            writer.writeStartDocument(Charsets.UTF_8.name(), "1.0")
            writer.writeStartElement("rss")
            writer.writeAttribute("version", "2.0")
            writer.writeNamespace("atom", ATOM_NAMESPACE)
            writer.writeStartElement("channel")
            val siteName = RssXmlText.clean(feed.metadata.siteName).ifBlank { "Blog" }
            val description = feed.metadata.defaultDescription?.let(RssXmlText::clean)?.takeUnless { it.isBlank() } ?: siteName
            writer.element("title", siteName)
            writer.element("link", rootUrl)
            writer.element("description", description)
            writer.element("language", RssXmlText.clean(feed.metadata.languageTag))
            writer.writeEmptyElement("atom", "link", ATOM_NAMESPACE)
            writer.writeAttribute("href", feedUrl)
            writer.writeAttribute("rel", "self")
            writer.writeAttribute("type", "application/rss+xml")
            feed.entries.forEach { entry ->
                writer.writeStartElement("item")
                writer.element("title", RssXmlText.postTitle(entry.post.title))
                writer.element("link", urls.postUrl(entry.post.id))
                // RSS readers interpret description as HTML after decoding the XML layer.
                writer.element("description", HtmlUtils.htmlEscape(summaryExtractor.extract(entry.post), Charsets.UTF_8.name()))
                writer.writeStartElement("guid")
                writer.writeAttribute("isPermaLink", "false")
                writer.writeCharacters("urn:uuid:${entry.post.id.value}")
                writer.writeEndElement()
                writer.element("pubDate", DateTimeFormatter.RFC_1123_DATE_TIME.format(entry.firstPublishedAt.atOffset(ZoneOffset.UTC)))
                writer.writeEndElement()
            }
            writer.writeEndElement()
            writer.writeEndElement()
            writer.writeEndDocument()
            writer.flush()
        } finally {
            writer.close()
        }
        return output.toByteArray()
    }

    private fun XMLStreamWriter.element(name: String, value: String) {
        writeStartElement(name)
        writeCharacters(value)
        writeEndElement()
    }

    private companion object {
        const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
    }
}
