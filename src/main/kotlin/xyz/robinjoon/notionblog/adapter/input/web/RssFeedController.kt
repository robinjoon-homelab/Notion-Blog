package xyz.robinjoon.notionblog.adapter.input.web

import org.springframework.http.CacheControl
import org.springframework.http.ETag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import xyz.robinjoon.notionblog.application.model.PostFeedLookupResult
import xyz.robinjoon.notionblog.application.service.GetPostFeedService
import java.security.MessageDigest
import java.util.HexFormat

@RestController
class RssFeedController(
    private val service: GetPostFeedService,
    private val renderer: RssFeedRenderer,
    private val urls: PublicBlogUrls,
) {
    @GetMapping("/feed.xml", produces = ["application/rss+xml"])
    fun feed(request: ServletWebRequest): ResponseEntity<ByteArray> {
        if (urls.feedUrl == null) {
            return unavailable()
        }
        val result = service.get()
        if (result !is PostFeedLookupResult.Found) {
            return unavailable()
        }
        val bytes = renderer.render(result.feed, urls)
        val etag = "\"${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))}\""
        val conditionalStatus = when {
            request.checkNotModified(etag) -> request.response?.status ?: HttpStatus.NOT_MODIFIED.value()

            // Spring 7.0 does not treat the wildcard as a match for safe methods.
            request.getHeaderValues(HttpHeaders.IF_NONE_MATCH).orEmpty().any { value -> ETag.parse(value).any { it.isWildcard } } ->
                HttpStatus.NOT_MODIFIED.value()

            else -> null
        }
        if (conditionalStatus != null) {
            return ResponseEntity.status(conditionalStatus).cacheControl(CacheControl.noCache()).eTag(etag).build()
        }
        return ResponseEntity.ok()
            .contentType(RSS_MEDIA_TYPE)
            .cacheControl(CacheControl.noCache())
            .eTag(etag)
            .body(bytes)
    }

    private fun unavailable(): ResponseEntity<ByteArray> = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .cacheControl(CacheControl.noStore())
        .build()

    private companion object {
        val RSS_MEDIA_TYPE = MediaType("application", "rss+xml", Charsets.UTF_8)
    }
}
