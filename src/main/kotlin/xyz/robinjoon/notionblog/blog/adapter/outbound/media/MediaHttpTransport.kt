package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

internal class MediaHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val contentEncoding: String?,
    val location: String?,
    val body: MediaResponseBody,
)

// 요청 하나를 보낸다. HttpRequest timeout은 응답 헤더까지만 적용되므로 본문 수신까지의 기한은 future 대기로 강제하고,
// 기한 초과·인터럽트 때는 교환을 취소해 연결을 닫는다. 인증·쿠키 헤더를 붙이지 않는다.
internal class MediaHttpTransport(
    private val client: HttpClient,
    private val maxBytes: Long,
) : AutoCloseable {
    fun exchange(
        url: URI,
        timeout: Duration,
    ): MediaHttpResponse {
        val request =
            try {
                HttpRequest
                    .newBuilder(url)
                    .GET()
                    .timeout(timeout)
                    .build()
            } catch (exception: IllegalArgumentException) {
                throw MediaFetchException("media URL cannot be requested", exception)
            }
        val response = awaitWithin(client.sendAsync(request, MediaBodyHandler(maxBytes)), timeout)
        val headers = response.headers()
        return MediaHttpResponse(
            statusCode = response.statusCode(),
            contentType = headers.firstValue(CONTENT_TYPE).orElse(null),
            contentEncoding = headers.firstValue(CONTENT_ENCODING).orElse(null),
            location = headers.firstValue(LOCATION).orElse(null),
            body = response.body(),
        )
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val CONTENT_TYPE = "Content-Type"
        const val CONTENT_ENCODING = "Content-Encoding"
        const val LOCATION = "Location"
    }
}
