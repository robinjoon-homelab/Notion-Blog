package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.PublicIndex
import xyz.robinjoon.notionblog.blog.application.model.PublicIndexEntry

// Follows the llmstxt.org proposal: a title, an optional summary quote, and Markdown link lists.
class LlmsTxtRenderer(
    private val summaries: PostSummaryExtractor,
) {
    fun render(
        index: PublicIndex,
        urls: PublicBlogUrls,
    ): ByteArray {
        val text =
            buildString {
                append("# ").append(line(index.metadata.siteName)).append("\n\n")
                index.metadata.defaultDescription
                    ?.let(::line)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { append("> ").append(it).append("\n\n") }
                append("## Posts\n\n")
                index.entries.forEach { append(entry(it, urls)).append('\n') }
                if (index.entries.isNotEmpty()) append('\n')
                append("## Optional\n\n")
                append("- [RSS](").append(urls.feedUrl).append("): 최근 글 구독 피드\n")
                append("- [Sitemap](").append(urls.sitemapUrl).append("): 전체 공개 URL 목록\n")
            }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun entry(
        entry: PublicIndexEntry,
        urls: PublicBlogUrls,
    ): String {
        val url = requireNotNull(urls.pageUrl(entry.post.id, entry.root)) { "A public blog origin is required" }
        val title = RssXmlText.postTitle(line(entry.post.title)).replace(linkTextSpecials) { "\\" + it.value }
        val summary = summaries.bodySummary(entry.post)
        return if (summary == null) "- [$title]($url)" else "- [$title]($url): $summary"
    }

    private fun line(value: String): String = RssXmlText.clean(value).replace(whitespace, " ").trim()

    private companion object {
        val whitespace = Regex("(?U)\\s+")
        val linkTextSpecials = Regex("[\\\\\\[\\]]")
    }
}
