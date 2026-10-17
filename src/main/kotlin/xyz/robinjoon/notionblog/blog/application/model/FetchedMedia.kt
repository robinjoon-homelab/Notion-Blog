package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import java.util.Locale

// 바이트 배열은 변경 가능하므로 받을 때와 꺼낼 때 모두 복사해 호출자 사이에 배열을 공유하지 않는다.
class FetchedMedia(
    val sourceKey: SourceMediaKey,
    val mediaKey: MediaKey,
    contentType: String,
    bytes: ByteArray,
) {
    val contentType: String = contentType.trim().lowercase(Locale.ROOT)
    private val content: ByteArray = bytes.copyOf()

    val bytes: ByteArray
        get() = content.copyOf()

    val size: Int
        get() = content.size

    init {
        require(content.isNotEmpty()) { "fetched media bytes must not be empty" }
        require(contentTypePattern.matches(this.contentType)) { "fetched media content type must be a type/subtype without parameters" }
    }

    private companion object {
        val contentTypePattern = Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$")
    }
}
