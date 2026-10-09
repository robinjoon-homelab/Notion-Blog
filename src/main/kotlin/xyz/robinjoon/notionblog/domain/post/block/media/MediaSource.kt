package xyz.robinjoon.notionblog.domain.post.block.media

import java.net.URI
import java.time.Instant

sealed interface MediaSource {
    data class External(
        val url: URI,
    ) : MediaSource

    data class SourceHosted(
        val url: URI,
        val expiresAt: Instant?,
    ) : MediaSource
}
