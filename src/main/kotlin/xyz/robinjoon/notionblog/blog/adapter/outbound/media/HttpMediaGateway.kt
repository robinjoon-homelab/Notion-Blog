package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaGateway
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

// Notion이 서명한 파일을 앱 소유 바이트로 내려받는다. 리다이렉트는 직접 따라가며 hop마다 주소 정책을 다시 적용한다.
// 실패 메시지에는 서명 query가 든 URL·응답 본문을 넣지 않는다. 운영 조립은 정책·클라이언트 기본값만 쓴다.
internal class HttpMediaGateway(
    private val limits: MediaFetchLimits,
    private val addressPolicy: MediaAddressPolicy = PublicAddressPolicy(),
    client: HttpClient = secureClient(limits.requestTimeout),
) : MediaGateway,
    AutoCloseable {
    private val transport = MediaHttpTransport(client, limits.maxBytes)

    override fun fetch(source: MediaSource.SourceHosted): FetchedMedia {
        val sourceKey = SourceMediaKey.of(source.url)
        val deadline = System.nanoTime() + limits.requestTimeout.toNanos()
        val response = followRedirects(source.url, deadline)
        val body = received(response)
        return FetchedMedia(sourceKey, MediaKey(body.sha256), MediaContentTypes.stored(response.contentType), body.bytes)
    }

    override fun close() {
        transport.close()
    }

    private fun followRedirects(
        url: URI,
        deadline: Long,
    ): MediaHttpResponse {
        var current = url
        repeat(limits.maxRedirects + 1) {
            requireAllowedWithin(current, deadline)
            val response = transport.exchange(current, remaining(deadline))
            if (response.statusCode !in redirectStatuses) return response
            current = redirectTarget(current, response.location)
        }
        throw MediaFetchException("media source exceeded the redirect limit")
    }

    // JDK DNS 조회에는 기한을 줄 수 없으므로 주소 검사를 가상 스레드에서 실행하고 남은 기한만큼만 기다린다.
    // 기한을 넘긴 검사는 결과를 버리며, 그 hop에는 연결하지 않는다.
    private fun requireAllowedWithin(
        url: URI,
        deadline: Long,
    ) {
        awaitWithin(CompletableFuture.runAsync({ addressPolicy.requireAllowed(url) }, addressCheckThreads), remaining(deadline))
    }

    private fun received(response: MediaHttpResponse): MediaResponseBody.Received {
        if (response.statusCode != OK) {
            throw MediaFetchException("media source responded with HTTP ${response.statusCode}")
        }
        val encoding = response.contentEncoding
        if (!encoding.isNullOrBlank() && !encoding.equals(IDENTITY, ignoreCase = true)) {
            throw MediaFetchException("media source returned an encoded body")
        }
        val body =
            when (val received = response.body) {
                is MediaResponseBody.Received -> received
                MediaResponseBody.TooLarge -> throw MediaFetchException("media exceeds the ${limits.maxBytes} byte limit")
                MediaResponseBody.Skipped -> throw MediaFetchException("media source response body was not read")
            }
        if (body.bytes.isEmpty()) throw MediaFetchException("media source returned no bytes")
        return body
    }

    private fun redirectTarget(
        current: URI,
        location: String?,
    ): URI {
        if (location.isNullOrBlank()) throw MediaFetchException("media redirect has no location")
        val target =
            try {
                current.resolve(URI(location.trim()))
            } catch (exception: URISyntaxException) {
                throw MediaFetchException("media redirect location is invalid", exception)
            }
        if (current.scheme.equals(HTTPS, ignoreCase = true) && !target.scheme.equals(HTTPS, ignoreCase = true)) {
            throw MediaFetchException("media redirect must stay on HTTPS")
        }
        return target
    }

    private fun remaining(deadline: Long): Duration {
        val nanos = deadline - System.nanoTime()
        if (nanos <= 0) throw MediaFetchException("media download exceeded its deadline")
        return Duration.ofNanos(nanos)
    }

    companion object {
        private const val OK = 200
        private const val HTTPS = "https"
        private const val IDENTITY = "identity"
        private val redirectStatuses = setOf(301, 302, 303, 307, 308)
        private val addressCheckThreads = Executor { task -> Thread.ofVirtual().name("media-address-check").start(task) }

        // Notion API 클라이언트와 공유하지 않는 전용 클라이언트다. 쿠키 저장소·인증기·프록시·자동 리다이렉트를 두지 않는다.
        fun secureClient(connectTimeout: Duration): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .build()
    }
}
