package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostPublication
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.time.Instant

data class LegacyPostRecord(
    val binding: PostSourceBinding,
    val title: String,
    val snapshot: LegacySnapshot?,
    val legacyPublic: Boolean,
    val firstPublishedAt: Instant?,
) {
    // 레거시 읽기 경로가 제공하던 글만, 제공하던 스냅숏의 사본으로 공개한다. 레거시 최초 게시 시각은 유지하고, 그 값이 없는 공개
    // 글은 스냅숏을 캡처한 시각을 쓴다.
    fun toManagedPost(copiedRevisionId: PostRevisionId?): Post {
        val publicRevisionId = copiedRevisionId?.takeIf { legacyPublic }
        val publishedAt = firstPublishedAt ?: snapshot?.capturedAt?.takeIf { publicRevisionId != null }
        val publication =
            publicRevisionId?.let { PostPublication(PostVisibility.PUBLIC, it, archived = false, generation = 0, publishedAt) }
                ?: PostPublication.initial(publishedAt)
        return Post.restore(binding.postId, binding.sourceDocument, copiedRevisionId, publication)
    }
}

// 저장된 스냅숏 JSON을 해석할 수 없으면 media는 비어 있다. 그래도 JSON 자체는 바꾸지 않고 복사한다.
data class LegacySnapshot(
    val capturedAt: Instant,
    val media: Set<MediaSource.SourceHosted>,
)

data class LegacyMigrationResult(
    val migrated: Int,
    val madePublic: Int,
)
