package xyz.robinjoon.notionblog.blog.domain.series

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.AggregateRoot
import java.util.UUID

@JvmInline
value class SeriesId(
    val value: UUID,
)

// 관리자가 정한 순서로 읽는 글 묶음이다. 글 없이 만들고 지울 수 있으며 글의 게시 상태·분류와 독립적이다. 글 하나가 여러 시리즈에
// 속하지 않는 규칙은 여러 시리즈에 걸치므로 저장소가 보장한다.
@AggregateRoot
@ConsistentCopyVisibility
data class Series private constructor(
    val id: SeriesId,
    val title: String,
    val description: String,
    val postIds: List<PostId>,
) {
    init {
        require(title.isNotEmpty()) { "series title must not be blank" }
        require(title.length <= MAX_TITLE_LENGTH) { "series title must be at most $MAX_TITLE_LENGTH characters" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "series description must be at most $MAX_DESCRIPTION_LENGTH characters"
        }
        require(postIds.toSet().size == postIds.size) { "a post must appear in a series only once" }
    }

    // 호출자의 목록이 나중에 바뀌어도 영향받지 않도록 변경할 수 없는 사본을 보관한다.
    fun update(
        title: String,
        description: String,
        postIds: List<PostId>,
    ): Series = of(id, title, description, postIds)

    // 이미 있는 글은 위치를 바꾸지 않는다.
    fun append(postId: PostId): Series = if (postId in postIds) this else of(id, title, description, postIds + postId)

    fun remove(postId: PostId): Series = of(id, title, description, postIds - postId)

    companion object {
        const val MAX_TITLE_LENGTH = 100
        const val MAX_DESCRIPTION_LENGTH = 300

        fun create(
            id: SeriesId,
            title: String,
            description: String,
        ): Series = of(id, title, description, emptyList())

        fun restore(
            id: SeriesId,
            title: String,
            description: String,
            postIds: List<PostId>,
        ): Series = of(id, title, description, postIds)

        private fun of(
            id: SeriesId,
            title: String,
            description: String,
            postIds: List<PostId>,
        ): Series = Series(id, title.trim(), description.trim(), java.util.List.copyOf(postIds))
    }
}
