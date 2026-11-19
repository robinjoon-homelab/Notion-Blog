package xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling

import org.springframework.scheduling.annotation.Scheduled
import xyz.robinjoon.notionblog.blog.application.port.input.RunNextMigrationItemUseCase
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PostMigrationScheduler(
    private val jobs: RunNextMigrationItemUseCase,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val leaseRenewals =
        ScheduledThreadPoolExecutor(1, Thread.ofVirtual().name("migration-lease").factory()).apply {
            removeOnCancelPolicy = true
        }

    @Scheduled(fixedRate = 2000)
    fun runNext() {
        if (!running.compareAndSet(false, true)) return
        try {
            runWithLeaseRenewals()
        } finally {
            running.set(false)
        }
    }

    private fun runWithLeaseRenewals() {
        // Renewal must run while the scheduled import thread is blocked on the source or file server.
        val renewal = leaseRenewals.scheduleWithFixedDelay(::renewLease, 30, 30, TimeUnit.SECONDS)
        try {
            SynchronizationFailureHandler("MIGRATION", isolateCheckedExceptions = true).run(jobs::runNext)
        } finally {
            renewal.cancel(false)
        }
    }

    private fun renewLease() = SynchronizationFailureHandler("MIGRATION_LEASE", isolateCheckedExceptions = true).run(jobs::renewActive)

    override fun close() {
        leaseRenewals.shutdownNow()
    }
}
