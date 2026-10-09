package xyz.robinjoon.notionblog.adapter.inbound.web

import org.springframework.web.util.HtmlUtils
import xyz.robinjoon.notionblog.application.model.PostFeed
import xyz.robinjoon.notionblog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

class RssFeedRenderer(
    private val summaryExtractor: RssSummaryExtractor,
) {
    fun render(
        feed: PostFeed,
        urls: PublicBlogUrls,
    ): ByteArray {
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
            writer.channelMetadata(feed.metadata, rootUrl, feedUrl)
            feed.entries.forEach { writer.item(it, urls) }
            writer.writeEndElement()
            writer.writeEndElement()
            writer.writeEndDocument()
            writer.flush()
        } finally {
            writer.close()
        }
        return output.toByteArray()
    }

    private fun XMLStreamWriter.channelMetadata(
        metadata: SiteMetadata,
        rootUrl: String,
        feedUrl: String,
    ) {
        val siteName = RssXmlText.clean(metadata.siteName).ifBlank { "Blog" }
        val description =
            metadata.defaultDescription
                ?.let(RssXmlText::clean)
                ?.takeUnless { it.isBlank() } ?: siteName
        element("title", siteName)
        element("link", rootUrl)
        element("description", description)
        element("language", RssXmlText.clean(metadata.languageTag))
        writeEmptyElement("atom", "link", ATOM_NAMESPACE)
        writeAttribute("href", feedUrl)
        writeAttribute("rel", "self")
        writeAttribute("type", "application/rss+xml")
    }

    private fun XMLStreamWriter.item(
        entry: PostFeedEntry,
        urls: PublicBlogUrls,
    ) {
        writeStartElement("item")
        element("title", RssXmlText.postTitle(entry.post.title))
        element("link", urls.postUrl(entry.post.id))
        // RSS readers interpret description as HTML after decoding the XML layer.
        element("description", HtmlUtils.htmlEscape(summaryExtractor.extract(entry.post), Charsets.UTF_8.name()))
        writeStartElement("guid")
        writeAttribute("isPermaLink", "false")
        writeCharacters("urn:uuid:${entry.post.id.value}")
        writeEndElement()
        element("pubDate", DateTimeFormatter.RFC_1123_DATE_TIME.format(entry.firstPublishedAt.atOffset(ZoneOffset.UTC)))
        writeEndElement()
    }

    private fun XMLStreamWriter.element(
        name: String,
        value: String,
    ) {
        writeStartElement(name)
        writeCharacters(value)
        writeEndElement()
    }

    private companion object {
        const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
    }
}
