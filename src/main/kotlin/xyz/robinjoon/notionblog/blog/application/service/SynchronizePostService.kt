package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.blog.application.model.RefreshPostResult
import xyz.robinjoon.notionblog.blog.application.port.input.RefreshPostUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizePostUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.RecoveredOperation
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

// 원본 문서를 새 초안 리비전으로 가져온다. 원본 조회와 파일 다운로드는 트랜잭션 밖에서 실행하며, 실패하면 진단 정보만 기록하고 최신
// 리비전과 게시 리비전은 그대로 둔다.
class SynchronizePostService(
    private val queries: SynchronizationQueryService,
    private val source: PostRevisionSource,
    private val media: CapturePostMediaService,
    private val applyService: ApplyImportedPostService,
    private val cancelService: CancelSynchronizationService,
    private val failures: OperationFailureReporter,
) : SynchronizePostUseCase,
    RefreshPostUseCase {
    override fun synchronize(postId: PostId) {
        val outcome = run(postId)
        if (outcome is Outcome.Failed) throw outcome.cause
    }

    // 관리자는 실패 종류만 보므로 원인은 여기서 보고한다. 예약 실행의 실패는 대신 다시 던져 스케줄러가 로그로 남긴다.
    override fun refresh(postId: PostId): RefreshPostResult =
        when (val outcome = run(postId)) {
            is Outcome.Done -> {
                outcome.result
            }

            is Outcome.Failed -> {
                failures.report(RecoveredOperation.POST_REFRESH, outcome.cause)
                RefreshPostResult.Failed(outcome.kind)
            }
        }

    private fun run(postId: PostId): Outcome {
        val context = queries.loadPost(postId)
        if (context == null) {
            cancelService.cancelPost(postId)
            return Outcome.Done(RefreshPostResult.NotFound)
        }
        return try {
            Outcome.Done(importRevision(context))
        } catch (exception: SourceException) {
            failed(postId, exception.toSyncFailureKind(), exception)
        } catch (exception: MediaFetchException) {
            failed(postId, SyncFailureKind.MEDIA, exception)
        } catch (exception: IllegalArgumentException) {
            failed(postId, SyncFailureKind.MAPPING, exception)
        }
    }

    private fun importRevision(context: PostSynchronizationContext): RefreshPostResult {
        val imported = source.fetchRevision(context.sourceDocument)
        require(imported.sourceDocument == context.sourceDocument) { "imported document must match the requested source document" }
        val changed = imported.sourceRevision != context.latestSourceRevision
        val newRevision = if (changed) imported.sourceHostedMedia() else emptySet()
        val repairs = context.pendingRepairs.associate { target -> target.revisionId to target.sources(imported) }
        return applyService.apply(context.postId, imported.takeIf { changed }, media.capture(newRevision, repairs))
    }

    private fun failed(
        postId: PostId,
        kind: SyncFailureKind,
        cause: RuntimeException,
    ): Outcome {
        if (cause.causedByInterruption()) throw cause
        applyService.recordFailure(postId, kind)
        return Outcome.Failed(kind, cause)
    }

    private sealed interface Outcome {
        data class Done(
            val result: RefreshPostResult,
        ) : Outcome

        data class Failed(
            val kind: SyncFailureKind,
            val cause: RuntimeException,
        ) : Outcome
    }
}
