package xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import xyz.robinjoon.notionblog.blog.application.model.CatalogSynchronization
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeCatalogUseCase
import java.util.concurrent.atomic.AtomicBoolean

// 글 컬렉션에 새로 생긴 문서를 등록한다. 일정 행이 없으며 실패한 스캔은 다음 주기에 다시 시도한다.
@ConditionalOnProperty(
    prefix = "blog.synchronization",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CatalogScanScheduler(
    private val catalogService: SynchronizeCatalogUseCase,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "\${blog.synchronization.catalog-interval-ms:300000}")
    fun scan() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            var result: CatalogSynchronization? = null
            SynchronizationFailureHandler("CATALOG").run { result = catalogService.synchronize() }
            result?.let(::report)
        } finally {
            running.set(false)
        }
    }

    private fun report(result: CatalogSynchronization) {
        if (result.registered > 0) {
            logger.info("Catalog scan registered {} posts", result.registered)
        }
    }
}
