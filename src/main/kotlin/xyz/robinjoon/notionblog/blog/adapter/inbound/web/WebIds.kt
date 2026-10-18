package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import java.util.UUID

// 경로·폼 값을 식별자로 해석한다. 형식이 틀리면 null이라 호출자가 404 또는 400으로 응답한다.
internal object WebIds {
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val mediaKeyPattern = Regex("[0-9a-f]{64}")

    fun postId(value: String): PostId? = uuid(value)?.let(::PostId)

    fun categoryId(value: String): CategoryId? = uuid(value)?.let(::CategoryId)

    fun revisionId(value: String): PostRevisionId? = uuid(value)?.let(::PostRevisionId)

    fun seriesId(value: String): SeriesId? = uuid(value)?.let(::SeriesId)

    fun mediaKey(value: String): MediaKey? = value.takeIf(mediaKeyPattern::matches)?.let(::MediaKey)

    private fun uuid(value: String): UUID? = value.takeIf(uuidPattern::matches)?.let(UUID::fromString)
}
