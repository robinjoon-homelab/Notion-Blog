package xyz.robinjoon.notionblog.blog.application.port.output.diagnostics

import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.post.PostId

interface SnapshotFailureReporter {
    fun report(
        failure: SnapshotContentException,
        operation: SnapshotFailureOperation,
        postId: PostId?,
    )
}

enum class SnapshotFailureOperation {
    POST_LOOKUP,
    FEED_LOOKUP,
    SNAPSHOT_REPAIR,
    ADMIN_PREVIEW,
}
