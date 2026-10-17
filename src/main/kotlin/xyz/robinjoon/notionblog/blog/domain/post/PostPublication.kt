package xyz.robinjoon.notionblog.blog.domain.post

import java.time.Instant

enum class PostVisibility {
    PRIVATE,
    PUBLIC,
}

// 독자가 볼 수 있는 내용과 글이 처음 게시된 시각이다. 모든 관리자 명령은 세대를 올리므로, 게시 취소·보관·복원 전에 준비된 게시 요청은
// 더 이상 일치하지 않아 글을 다시 공개할 수 없다. 최초 게시 시각은 한 번 기록한 뒤 이후 명령에서도 유지한다.
data class PostPublication(
    val visibility: PostVisibility,
    val publishedRevisionId: PostRevisionId?,
    val archived: Boolean,
    val generation: Long,
    val firstPublishedAt: Instant?,
) {
    init {
        require(generation >= 0) { "publication generation must not be negative" }
        require(visibility == PostVisibility.PRIVATE || publishedRevisionId != null) { "a public post must have a published revision" }
        require(visibility == PostVisibility.PRIVATE || firstPublishedAt != null) { "a public post must have a first publication time" }
        require(!archived || visibility == PostVisibility.PRIVATE) { "an archived post must be private" }
    }

    val isPublic: Boolean
        get() = visibility == PostVisibility.PUBLIC

    internal fun published(
        revisionId: PostRevisionId,
        publishedAt: Instant,
    ): PostPublication =
        copy(
            visibility = PostVisibility.PUBLIC,
            publishedRevisionId = revisionId,
            generation = nextGeneration(),
            firstPublishedAt = firstPublishedAt ?: publishedAt,
        )

    internal fun withdrawn(): PostPublication = copy(visibility = PostVisibility.PRIVATE, generation = nextGeneration())

    internal fun archived(): PostPublication = copy(visibility = PostVisibility.PRIVATE, archived = true, generation = nextGeneration())

    internal fun unarchived(): PostPublication = copy(archived = false, generation = nextGeneration())

    private fun nextGeneration(): Long = Math.addExact(generation, 1)

    companion object {
        fun initial(firstPublishedAt: Instant? = null): PostPublication =
            PostPublication(PostVisibility.PRIVATE, null, archived = false, generation = 0, firstPublishedAt = firstPublishedAt)
    }
}
