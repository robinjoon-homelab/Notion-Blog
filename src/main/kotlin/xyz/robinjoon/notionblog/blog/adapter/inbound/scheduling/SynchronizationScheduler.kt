package xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizationQueryUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizePostUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeSiteConfigurationUseCase
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
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
    private val postService: SynchronizePostUseCase,
    private val clock: Clock,
    private val dueBatchSize: Int,
) {
    private val running = AtomicBoolean(false)

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

    // The lookup and each target run through their own SynchronizationFailureHandler, which decides what is isolated.
    private fun findDueTargets(): List<SyncTarget> {
        var targets = emptyList<SyncTarget>()
        SynchronizationFailureHandler("LOOKUP").run { targets = queryService.findDueTargets(clock.instant(), dueBatchSize) }
        return targets
    }

    private fun synchronizeTarget(target: SyncTarget) {
        SynchronizationFailureHandler(target.kind()).run { dispatch(target) }
    }

    private fun dispatch(target: SyncTarget) {
        when (target) {
            SyncTarget.SiteConfiguration -> siteConfigurationService.synchronize()

            // 게시 트리는 더 이상 아무것도 결정하지 않는다. 남겨 둔 그 일정은 실행 대상으로 반환되지 않는다.
            is SyncTarget.Publication -> Unit

            is SyncTarget.Post -> postService.synchronize(target.postId)
        }
    }

    private fun SyncTarget.kind(): String =
        when (this) {
            SyncTarget.SiteConfiguration -> "SITE_CONFIGURATION"
            is SyncTarget.Publication -> "PUBLICATION"
            is SyncTarget.Post -> "POST"
        }
}
