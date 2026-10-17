package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import java.time.Duration

// requestTimeout은 리다이렉트를 포함한 파일 하나의 전체 내려받기 기한이다.
internal data class MediaFetchLimits(
    val maxBytes: Long,
    val requestTimeout: Duration,
    val maxRedirects: Int,
) {
    init {
        require(maxBytes in 1..MAX_BUFFER_BYTES) { "media max bytes must be between 1 and $MAX_BUFFER_BYTES" }
        require(requestTimeout.isPositive) { "media request timeout must be positive" }
        require(maxRedirects in 0..MAX_REDIRECT_LIMIT) { "media max redirects must be between 0 and $MAX_REDIRECT_LIMIT" }
    }

    private companion object {
        // 메모리 버퍼 하나로 읽으므로 JVM 배열 상한과 PostgreSQL bytea 한 값의 상한(1GB) 아래로 둔다.
        const val MAX_BUFFER_BYTES = 1_000_000_000L
        const val MAX_REDIRECT_LIMIT = 10
    }
}
