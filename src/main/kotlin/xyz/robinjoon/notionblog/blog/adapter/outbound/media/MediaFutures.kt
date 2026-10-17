package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// 기한 안에서만 결과를 기다린다. 기한 초과·인터럽트 때는 작업을 취소하고, 인터럽트면 호출 스레드의 상태를 복구한 뒤
// 원인을 보존한 MediaFetchException으로 알린다. Error처럼 복구할 수 없는 원인은 그대로 전파한다.
internal fun <T> awaitWithin(
    future: CompletableFuture<T>,
    timeout: Duration,
): T =
    try {
        future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    } catch (exception: TimeoutException) {
        future.cancel(true)
        throw MediaFetchException("media download exceeded its deadline", exception)
    } catch (exception: InterruptedException) {
        future.cancel(true)
        Thread.currentThread().interrupt()
        throw MediaFetchException("media download was interrupted", exception)
    } catch (exception: ExecutionException) {
        throw failure(exception)
    }

private fun failure(exception: ExecutionException): Throwable =
    when (val cause = exception.cause) {
        is MediaFetchException -> cause
        is HttpTimeoutException -> MediaFetchException("media download timed out", cause)
        is IOException -> MediaFetchException("media download could not be completed", cause)
        is RuntimeException -> MediaFetchException("media download failed", cause)
        null -> MediaFetchException("media download could not be completed", exception)
        else -> cause
    }
