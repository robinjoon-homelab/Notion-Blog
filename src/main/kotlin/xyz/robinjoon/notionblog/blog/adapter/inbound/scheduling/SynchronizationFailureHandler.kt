package xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling

import org.slf4j.LoggerFactory
import org.springframework.scheduling.support.TaskUtils
import org.springframework.util.ErrorHandler
import java.lang.reflect.UndeclaredThrowableException

// Recoverable failures are logged by kind and type only. Migration opts in for checked database failures, whose
// causes may include JDBC parameters. Interruptions restore the flag and propagate.
internal class SynchronizationFailureHandler(
    private val targetKind: String,
    private val isolateCheckedExceptions: Boolean = false,
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
        if (failure !is RuntimeException && (!isolateCheckedExceptions || failure !is Exception)) {
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

    private fun causedByInterruption(failure: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>()
        return generateSequence<Throwable>(failure) { it.cause }
            .takeWhile(seen::add)
            .any { it is InterruptedException }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SynchronizationScheduler::class.java)
    }
}
