package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.application.model.BlogPage

// Writes schema.org JSON-LD for the page head. The output is embedded in a non-executable script element, so markup
// characters are escaped as JSON unicode sequences; no title can close the element or change the CSP evaluation.
class JsonLdWriter(
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    fun write(
        page: BlogPage,
        description: String?,
        urls: PublicBlogUrls,
    ): String {
        val document = if (page.root) webSite(page, description, urls) else blogPosting(page, description, urls)
        return mapper
            .writeValueAsString(document)
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
    }

    private fun webSite(
        page: BlogPage,
        description: String?,
        urls: PublicBlogUrls,
    ): Map<String, Any> =
        buildMap {
            put("@context", SCHEMA_CONTEXT)
            put("@type", "WebSite")
            put("name", page.site.metadata.siteName)
            (page.site.metadata.defaultDescription ?: description)?.let { put("description", it) }
            put("inLanguage", page.site.metadata.languageTag)
            urls.rootUrl?.let { put("url", it) }
        }

    private fun blogPosting(
        page: BlogPage,
        description: String?,
        urls: PublicBlogUrls,
    ): Map<String, Any> =
        buildMap {
            put("@context", SCHEMA_CONTEXT)
            put("@type", "BlogPosting")
            put("headline", page.post.title)
            description?.let { put("description", it) }
            page.firstPublishedAt?.let { put("datePublished", it.toPublicTimestamp()) }
            put("dateModified", page.lastModifiedAt.toPublicTimestamp())
            put("inLanguage", page.site.metadata.languageTag)
            urls.pageUrl(page.post.id, root = false)?.let {
                put("url", it)
                put("mainEntityOfPage", it)
            }
            put("isPartOf", isPartOf(page, urls))
        }

    private fun isPartOf(
        page: BlogPage,
        urls: PublicBlogUrls,
    ): Map<String, Any> =
        buildMap {
            put("@type", "WebSite")
            put("name", page.site.metadata.siteName)
            urls.rootUrl?.let { put("url", it) }
        }

    private companion object {
        const val SCHEMA_CONTEXT = "https://schema.org"
    }
}
