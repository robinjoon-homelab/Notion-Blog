package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import xyz.robinjoon.notionblog.blog.application.model.PostFeedLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostFeedUseCase

@RestController
class RssFeedController(
    private val service: GetPostFeedUseCase,
    private val renderer: RssFeedRenderer,
    private val urls: PublicBlogUrls,
) {
    @GetMapping("/feed.xml", produces = ["application/rss+xml"])
    fun feed(request: ServletWebRequest): ResponseEntity<ByteArray> {
        if (urls.feedUrl == null) {
            return ConditionalRepresentation.unavailable()
        }
        val result = service.get()
        if (result !is PostFeedLookupResult.Found) {
            return ConditionalRepresentation.unavailable()
        }
        return ConditionalRepresentation.respond(request, renderer.render(result.feed, urls), rssMediaType)
    }

    private companion object {
        val rssMediaType = MediaType("application", "rss+xml", Charsets.UTF_8)
    }
}
