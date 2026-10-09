package xyz.robinjoon.notionblog.adapter.outbound.diagnostics

import org.slf4j.LoggerFactory
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.domain.post.PostId

class Slf4jSnapshotFailureReporter : SnapshotFailureReporter {
    private val logger = LoggerFactory.getLogger(Slf4jSnapshotFailureReporter::class.java)

    override fun report(
        failure: SnapshotContentException,
        operation: SnapshotFailureOperation,
        postId: PostId?,
    ) {
        logger.error(
            "Snapshot content failure operation={} postId={} errorType={} causeType={}",
            operation.name,
            postId?.value?.toString() ?: "none",
            failure.javaClass.name,
            failure.cause?.javaClass?.name ?: "none",
        )
    }
}
