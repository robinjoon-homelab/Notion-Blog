package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

// 관리자가 검토한 대상이다. 화면에 보인 리비전과 그때의 게시 세대를 담는다.
data class PublishPostCommand(
    val postId: PostId,
    val revisionId: PostRevisionId,
    val generation: Long,
)

sealed interface RefreshPostResult {
    data class Updated(
        val revisionId: PostRevisionId,
    ) : RefreshPostResult

    data object Unchanged : RefreshPostResult

    data class Failed(
        val kind: SyncFailureKind,
    ) : RefreshPostResult

    data object NotFound : RefreshPostResult
}

sealed interface PublishPostResult {
    data object Published : PublishPostResult

    data object AlreadyPublished : PublishPostResult

    // 검토한 리비전이 더 이상 최신이 아니거나, 미리보기를 보여 준 뒤 게시 상태가 바뀌었다.
    data object StaleRevision : PublishPostResult

    data object MediaNotReady : PublishPostResult

    data object Archived : PublishPostResult

    data object NotFound : PublishPostResult
}

sealed interface UnpublishPostResult {
    data object Unpublished : UnpublishPostResult

    data object AlreadyUnpublished : UnpublishPostResult

    data object NotFound : UnpublishPostResult
}

sealed interface ArchivePostResult {
    data object Archived : ArchivePostResult

    data object AlreadyArchived : ArchivePostResult

    data object NotFound : ArchivePostResult
}

sealed interface RestorePostResult {
    data object Restored : RestorePostResult

    data object NotArchived : RestorePostResult

    data object NotFound : RestorePostResult
}
