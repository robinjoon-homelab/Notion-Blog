package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

internal sealed interface MediaResponseBody {
    // 응답 버퍼에서 새로 만든 배열이다. 만든 쪽은 이 배열을 보관하거나 다시 쓰지 않는다.
    class Received(
        val bytes: ByteArray,
        val sha256: String,
    ) : MediaResponseBody

    data object TooLarge : MediaResponseBody

    data object Skipped : MediaResponseBody
}

// 200 응답만 읽는다. 상태가 200이 아니거나 선언된 길이가 상한을 넘으면 본문을 읽지 않고 구독을 취소해 연결을 닫는다.
internal class MediaBodyHandler(
    private val maxBytes: Long,
) : HttpResponse.BodyHandler<MediaResponseBody> {
    override fun apply(responseInfo: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<MediaResponseBody> {
        val declaredLength = responseInfo.headers().firstValueAsLong(CONTENT_LENGTH)
        return when {
            responseInfo.statusCode() != OK -> CancellingBodySubscriber(MediaResponseBody.Skipped)
            declaredLength.isPresent && declaredLength.asLong > maxBytes -> CancellingBodySubscriber(MediaResponseBody.TooLarge)
            else -> BoundedBodySubscriber(maxBytes, declaredLength.orElse(0).coerceIn(0, maxBytes).toInt())
        }
    }

    private companion object {
        const val OK = 200
        const val CONTENT_LENGTH = "Content-Length"
    }
}

internal class CancellingBodySubscriber(
    private val result: MediaResponseBody,
) : HttpResponse.BodySubscriber<MediaResponseBody> {
    private val body = CompletableFuture<MediaResponseBody>()

    override fun getBody(): CompletionStage<MediaResponseBody> = body

    override fun onSubscribe(subscription: Flow.Subscription) {
        body.complete(result)
        subscription.cancel()
    }

    override fun onNext(item: List<ByteBuffer>) = Unit

    override fun onError(throwable: Throwable) {
        body.completeExceptionally(throwable)
    }

    override fun onComplete() {
        body.complete(result)
    }
}

// 길이를 모르는 청크 응답도 상한을 넘는 순간 구독을 취소한다. 읽는 동안 SHA-256을 함께 계산한다.
// 선언된 길이(상한 이하)로 버퍼를 미리 잡아 큰 파일의 반복 확장 복사를 피한다.
// Reactive Streams 신호는 직렬로 전달되므로 필드에 별도 동기화가 필요 없다.
internal class BoundedBodySubscriber(
    private val maxBytes: Long,
    initialCapacity: Int,
) : HttpResponse.BodySubscriber<MediaResponseBody> {
    private val body = CompletableFuture<MediaResponseBody>()
    private val buffer = ByteArrayOutputStream(initialCapacity)
    private val digest = MessageDigest.getInstance(SHA_256)
    private var subscription: Flow.Subscription? = null
    private var receivedBytes = 0L

    override fun getBody(): CompletionStage<MediaResponseBody> = body

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(item: List<ByteBuffer>) {
        if (body.isDone) return
        receivedBytes += item.sumOf { chunk -> chunk.remaining().toLong() }
        if (receivedBytes > maxBytes) {
            body.complete(MediaResponseBody.TooLarge)
            subscription?.cancel()
            return
        }
        item.forEach(::append)
        subscription?.request(1)
    }

    override fun onError(throwable: Throwable) {
        body.completeExceptionally(throwable)
    }

    override fun onComplete() {
        body.complete(MediaResponseBody.Received(buffer.toByteArray(), HexFormat.of().formatHex(digest.digest())))
    }

    private fun append(chunk: ByteBuffer) {
        val bytes = ByteArray(chunk.remaining())
        chunk.get(bytes)
        digest.update(bytes)
        buffer.writeBytes(bytes)
    }

    private companion object {
        const val SHA_256 = "SHA-256"
    }
}
