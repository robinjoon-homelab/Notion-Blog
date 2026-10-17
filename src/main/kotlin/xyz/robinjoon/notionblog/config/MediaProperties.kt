package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 앱이 보관하는 Notion 업로드 파일의 내려받기 한도다. maxBytes는 파일 하나, maxImportBytes는 글 하나를 가져올 때의 합계다.
// requestTimeout은 리다이렉트를 포함한 파일 하나의 전체 기한이다.
@ConfigurationProperties("blog.media")
data class MediaProperties(
    val maxBytes: Long = 26_214_400,
    val maxImportBytes: Long = 104_857_600,
    val requestTimeout: Duration = Duration.ofSeconds(10),
    val maxRedirects: Int = 3,
) {
    init {
        require(maxBytes in 1..MAX_FILE_BYTES) { "blog.media.max-bytes must be between 1 and $MAX_FILE_BYTES" }
        require(maxImportBytes >= maxBytes) { "blog.media.max-import-bytes must not be smaller than blog.media.max-bytes" }
        require(requestTimeout.isPositive) { "blog.media.request-timeout must be positive" }
        require(maxRedirects in 0..MAX_REDIRECTS) { "blog.media.max-redirects must be between 0 and $MAX_REDIRECTS" }
    }

    private companion object {
        const val MAX_FILE_BYTES = 1_000_000_000L
        const val MAX_REDIRECTS = 10
    }
}
