package xyz.robinjoon.notionblog.adapter.inbound.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import xyz.robinjoon.notionblog.application.port.input.SynchronizationQueryUseCase
import xyz.robinjoon.notionblog.application.port.input.SynchronizePostUseCase
import xyz.robinjoon.notionblog.application.port.input.SynchronizePublicationUseCase
import xyz.robinjoon.notionblog.application.port.input.SynchronizeSiteConfigurationUseCase
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

@ConditionalOnProperty(
    prefix = "blog.synchronization",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SynchronizationScheduler(
    private val queryService: SynchronizationQueryUseCase,
    private val siteConfigurationService: SynchronizeSiteConfigurationUseCase,
    private val publicationService: SynchronizePublicationUseCase,
    private val postService: SynchronizePostUseCase,
    private val clock: Clock,
    private val dueBatchSize: Int,
) {
    private val running = AtomicBoolean(false)
    private val logger = LoggerFactory.getLogger(SynchronizationScheduler::class.java)

    init {
        require(dueBatchSize > 0) { "due batch size must be positive" }
    }

    @Scheduled(fixedDelayString = "\${blog.synchronization.interval-ms:60000}")
    fun synchronizeDue() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            findDueTargets().forEach(::synchronizeTarget)
        } finally {
            running.set(false)
        }
    }

    // quality-exception: 조회 실패는 다음 tick에서 재시도하며 중단 신호는 handleFailure에서 복구하고 전파한다.
    @Suppress("TooGenericExceptionCaught")
    private fun findDueTargets(): List<SyncTarget> =
        try {
            queryService.findDueTargets(clock.instant(), dueBatchSize)
        } catch (exception: RuntimeException) {
            handleFailure("LOOKUP", exception)
            emptyList()
        }

    // quality-exception: 한 대상의 런타임 실패를 격리하되 중단 신호는 복구하고 원래 예외를 전파한다.
    @Suppress("TooGenericExceptionCaught")
    private fun synchronizeTarget(target: SyncTarget) {
        try {
            dispatch(target)
        } catch (exception: RuntimeException) {
            handleFailure(target.kind(), exception)
        }
    }

    private fun dispatch(target: SyncTarget) {
        when (target) {
            SyncTarget.SiteConfiguration -> siteConfigurationService.synchronize()
            is SyncTarget.Publication -> publicationService.synchronize()
            is SyncTarget.Post -> postService.synchronize(target.postId)
        }
    }

    private fun handleFailure(
        targetKind: String,
        exception: RuntimeException,
    ) {
        val seen = mutableSetOf<Throwable>()
        val interrupted =
            generateSequence<Throwable>(exception) { it.cause }
                .takeWhile(seen::add)
                .any { it is InterruptedException }
        if (interrupted || Thread.currentThread().isInterrupted) {
            Thread.currentThread().interrupt()
            throw exception
        }
        logger.error(
            "Synchronization target failed targetKind={} errorType={}",
            targetKind,
            exception::class.simpleName ?: "RuntimeException",
        )
    }

    private fun SyncTarget.kind(): String =
        when (this) {
            SyncTarget.SiteConfiguration -> "SITE_CONFIGURATION"
            is SyncTarget.Publication -> "PUBLICATION"
            is SyncTarget.Post -> "POST"
        }
}
