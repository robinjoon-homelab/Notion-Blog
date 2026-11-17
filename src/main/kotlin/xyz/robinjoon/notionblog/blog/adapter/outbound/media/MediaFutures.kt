package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

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
        throw mediaFailure("media download exceeded its deadline", "파일 다운로드가 제한 시간을 초과했습니다.", exception)
    } catch (exception: InterruptedException) {
        future.cancel(true)
        Thread.currentThread().interrupt()
        throw mediaFailure("media download was interrupted", "파일 다운로드가 중단되었습니다.", exception)
    } catch (exception: ExecutionException) {
        throw failure(exception)
    }

private fun failure(exception: ExecutionException): Throwable =
    when (val cause = exception.cause) {
        is MediaFetchException -> cause
        is HttpTimeoutException -> mediaFailure("media download timed out", "파일 서버 응답 대기 시간이 초과되었습니다.", cause)
        is IOException -> mediaFailure("media download could not be completed", networkReason(cause), cause)
        is RuntimeException -> mediaFailure("media download failed", networkReason(cause), cause)
        null -> mediaFailure("media download could not be completed", "파일 다운로드 작업이 실패했습니다.", exception)
        else -> cause
    }

private fun networkReason(cause: Throwable): String =
    generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).mapNotNull(::knownNetworkReason).firstOrNull()
        ?: "파일 서버와 통신 중 연결이 끊기거나 다운로드가 실패했습니다."

private fun knownNetworkReason(cause: Throwable): String? =
    when (cause) {
        is UnknownHostException -> "파일 서버의 DNS 주소를 확인하지 못했습니다."
        is HttpTimeoutException, is SocketTimeoutException -> "파일 서버 응답 대기 시간이 초과되었습니다."
        is SSLException -> "파일 서버와 보안 연결을 맺지 못했습니다."
        is ConnectException -> "파일 서버에 연결하지 못했습니다."
        else -> null
    }

private const val MAX_CAUSE_DEPTH = 16
