package xyz.robinjoon.notionblog.blog.domain.post.block.content

import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.net.URI

enum class MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    PDF,
}

sealed interface MediaBlockContent : BlockContent {
    data class Media(
        val mediaType: MediaType,
        val source: MediaSource,
        val fileName: String?,
        val caption: List<InlineContent> = emptyList(),
    ) : MediaBlockContent {
        init {
            require(fileName?.isBlank() != true) { "media file name must not be blank" }
        }
    }

    data class Bookmark(
        val url: URI,
        val caption: List<InlineContent> = emptyList(),
    ) : MediaBlockContent

    data class LinkPreview(
        val url: URI,
    ) : MediaBlockContent

    data class Embed(
        val url: URI,
        val caption: List<InlineContent> = emptyList(),
    ) : MediaBlockContent
}
