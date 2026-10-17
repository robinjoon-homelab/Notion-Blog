package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey

// 저장된 파일이다. 배열은 변경할 수 있으므로 바이트를 받을 때와 내보낼 때 복사한다.
class MediaContent(
    val key: MediaKey,
    val contentType: String,
    bytes: ByteArray,
) {
    private val content: ByteArray = bytes.copyOf()

    val bytes: ByteArray
        get() = content.copyOf()

    val size: Int
        get() = content.size
}

sealed interface MediaLookupResult {
    class Found(
        val media: MediaContent,
    ) : MediaLookupResult

    data object NotFound : MediaLookupResult
}
