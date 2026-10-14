package xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling

import org.slf4j.LoggerFactory
import org.springframework.scheduling.support.TaskUtils
import org.springframework.util.ErrorHandler
import java.lang.reflect.UndeclaredThrowableException

// Spring's failure contract for scheduled work, applied to one synchronization boundary. A RuntimeException is logged
// by kind and type only, never by message or stack, so the remaining targets still run and the next tick retries.
// A RuntimeException caused by an interruption restores the interrupt flag and is rethrown unchanged, as is every
// other Throwable.
internal class SynchronizationFailureHandler(
    private val targetKind: String,
) : ErrorHandler {
    // Runs the work on the calling thread inside Spring's error handling runnable. Spring would hand this handler the
    // cause of an UndeclaredThrowableException, which may be null, so that wrapper is kept for this call only and
    // handled as the original RuntimeException after the runnable returns.
    fun run(work: Runnable) {
        var undeclared: UndeclaredThrowableException? = null
        TaskUtils
            .decorateTaskWithErrorHandler(
                {
                    try {
                        work.run()
                    } catch (wrapper: UndeclaredThrowableException) {
                        undeclared = wrapper
                    }
                },
                this,
                false,
            ).run()
        undeclared?.let(::handleError)
    }

    override fun handleError(failure: Throwable) {
        if (failure !is RuntimeException) {
            throw failure
        }
        if (Thread.currentThread().isInterrupted || causedByInterruption(failure)) {
            Thread.currentThread().interrupt()
            throw failure
        }
        logger.error(
            "Synchronization target failed targetKind={} errorType={}",
            targetKind,
            failure::class.simpleName ?: "RuntimeException",
        )
    }

    private fun causedByInterruption(failure: RuntimeException): Boolean {
        val seen = mutableSetOf<Throwable>()
        return generateSequence<Throwable>(failure) { it.cause }
            .takeWhile(seen::add)
            .any { it is InterruptedException }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SynchronizationScheduler::class.java)
    }
}
