package xyz.robinjoon.notionblog.application.port.output.diagnostics

import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.domain.post.PostId

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
}
