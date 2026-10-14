package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.PublicIndex
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.xml.stream.XMLOutputFactory

class SitemapRenderer {
    fun render(
        index: PublicIndex,
        urls: PublicBlogUrls,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output, Charsets.UTF_8.name())
        try {
            writer.writeStartDocument(Charsets.UTF_8.name(), "1.0")
            writer.writeStartElement("urlset")
            writer.writeDefaultNamespace(SITEMAP_NAMESPACE)
            index.entries.forEach { entry ->
                val location = requireNotNull(urls.pageUrl(entry.post.id, entry.root)) { "A public blog origin is required" }
                writer.writeStartElement("url")
                writer.writeStartElement("loc")
                writer.writeCharacters(location)
                writer.writeEndElement()
                writer.writeStartElement("lastmod")
                writer.writeCharacters(DateTimeFormatter.ISO_INSTANT.format(entry.lastModifiedAt.truncatedTo(ChronoUnit.SECONDS)))
                writer.writeEndElement()
                writer.writeEndElement()
            }
            writer.writeEndElement()
            writer.writeEndDocument()
            writer.flush()
        } finally {
            writer.close()
        }
        return output.toByteArray()
    }

    private companion object {
        const val SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9"
    }
}
