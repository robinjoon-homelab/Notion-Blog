package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.CapturedMedia
import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.RecoveredOperation
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaGateway
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import java.time.Clock

// 트랜잭션 밖에서 파일을 내려받는다. 게이트웨이는 파일 하나의 크기를, 이 서비스는 가져오기 한 번의 총량을 제한한다. 새 리비전의 파일은
// 모두 다시 내려받으므로, 그 연결은 원본이 해당 리비전에 제공한 바이트를 가리킨다.
class CapturePostMediaService(
    private val gateway: MediaGateway,
    private val maxImportBytes: Long,
    private val failures: OperationFailureReporter,
    private val clock: Clock,
) {
    init {
        require(maxImportBytes > 0) { "maximum import size must be positive" }
    }

    // 새 리비전의 파일은 모두 복사해야 하며, 하나라도 실패하면 가져오기 전체가 실패한다. 복구 대상은 이후 동기화가 다시 시도하는 누락
    // 레거시 사본이므로, 일반적인 실패는 누락으로 보이게 남기고 이미 만료된 URL은 요청하지 않는다. 각 복구는 내려받은 대상 리비전을
    // 기억하므로 그 리비전만 채울 수 있다.
    fun capture(
        newRevision: Collection<MediaSource.SourceHosted>,
        repairs: Map<PostRevisionId, Collection<MediaSource.SourceHosted>>,
    ): CapturedMedia {
        val budget = ImportBudget(maxImportBytes)
        val fetched =
            newRevision.groupBy(::sourceKey).values.map { sources ->
                budget.take(fetchConsistent(sources)) ?: throw MediaFetchException("files of one import exceed the configured total size")
            }
        val repaired =
            repairs.mapValues { (_, sources) ->
                unexpired(sources).groupBy(::sourceKey).values.mapNotNull { group -> recover(group)?.let(budget::take) }
            }
        return CapturedMedia(fetched, repaired)
    }

    // 서명만 다른 URL은 같은 원본 파일을 가리킨다. 한 이름 뒤에 서로 다른 바이트가 있으면 어느 사본도 연결하지 않고 거부한다.
    private fun fetchConsistent(sources: List<MediaSource.SourceHosted>): FetchedMedia {
        val files = sources.distinctBy { it.url }.map(gateway::fetch)
        if (files.map(FetchedMedia::mediaKey).distinct().size > 1) {
            throw MediaFetchException("one source file name resolved to different bytes")
        }
        return files.first()
    }

    private fun recover(sources: List<MediaSource.SourceHosted>): FetchedMedia? =
        try {
            fetchConsistent(sources)
        } catch (exception: MediaFetchException) {
            if (exception.causedByInterruption()) throw exception
            failures.report(RecoveredOperation.LEGACY_MEDIA_CAPTURE, exception)
            null
        }

    private fun unexpired(sources: Collection<MediaSource.SourceHosted>): List<MediaSource.SourceHosted> {
        val now = clock.instant()
        return sources.filter { source -> source.expiresAt?.isAfter(now) != false }
    }

    private fun sourceKey(source: MediaSource.SourceHosted): SourceMediaKey = SourceMediaKey.of(source.url)

    private class ImportBudget(
        private var remaining: Long,
    ) {
        fun take(media: FetchedMedia): FetchedMedia? {
            if (media.size > remaining) return null
            remaining -= media.size
            return media
        }
    }
}

// 취소된 스레드는 일반 실패로 기록하지 않고 멈춰야 한다.
internal fun Throwable.causedByInterruption(): Boolean =
    Thread.currentThread().isInterrupted || generateSequence(this) { it.cause }.any { it is InterruptedException }
