package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import java.net.URI

class PublicBlogUrls(
    private val publicBaseUri: URI?,
) {
    val rootUrl: String? = publicBaseUri?.resolve("/")?.toASCIIString()
    val feedUrl: String? = publicBaseUri?.resolve("/feed.xml")?.toASCIIString()
    val sitemapUrl: String? = publicBaseUri?.resolve("/sitemap.xml")?.toASCIIString()
    val llmsTxtUrl: String? = publicBaseUri?.resolve("/llms.txt")?.toASCIIString()

    // The publication root is served at `/`, so that is its canonical address rather than `/posts/{rootId}`.
    fun pageUrl(
        postId: PostId,
        root: Boolean,
    ): String? =
        when {
            publicBaseUri == null -> null
            root -> rootUrl
            else -> postUrl(postId)
        }

    fun postUrl(postId: PostId): String =
        requireNotNull(publicBaseUri) { "A public blog origin is required" }
            .resolve("/posts/${postId.value}")
            .toASCIIString()
}
