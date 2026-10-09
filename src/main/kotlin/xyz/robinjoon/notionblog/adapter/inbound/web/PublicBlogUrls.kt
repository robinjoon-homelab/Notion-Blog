package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.domain.post.PostId
import java.net.URI

class PublicBlogUrls(
    private val publicBaseUri: URI?,
) {
    val rootUrl: String? = publicBaseUri?.resolve("/")?.toASCIIString()
    val feedUrl: String? = publicBaseUri?.resolve("/feed.xml")?.toASCIIString()

    fun postUrl(postId: PostId): String =
        requireNotNull(publicBaseUri) { "A public blog origin is required" }
            .resolve("/posts/${postId.value}")
            .toASCIIString()
}
