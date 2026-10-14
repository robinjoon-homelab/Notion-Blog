package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// Every crawler, including AI training, search, and user-requested agents, may read all public pages. The file reads
// no stored data, so it stays available while the blog is still initializing.
@RestController
class RobotsTxtController(
    private val urls: PublicBlogUrls,
) {
    @GetMapping("/robots.txt", produces = ["text/plain"])
    fun robots(): ResponseEntity<ByteArray> {
        val body =
            buildString {
                append("User-agent: *\nAllow: /\n")
                urls.sitemapUrl?.let { append("\nSitemap: ").append(it).append('\n') }
            }
        return ResponseEntity
            .ok()
            .contentType(MediaType("text", "plain", Charsets.UTF_8))
            .body(body.toByteArray(Charsets.UTF_8))
    }
}
