package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
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
        throw mediaFailure("media source exceeded the redirect limit", "파일 서버의 리다이렉트 횟수가 허용 한도를 초과했습니다.")
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
            throw MediaFetchException(
                "media source responded with HTTP ${response.statusCode}",
                diagnostic = responseFailure("파일 서버가 다운로드를 거절하거나 오류를 반환했습니다.", response.statusCode),
            )
        }
        val encoding = response.contentEncoding
        if (!encoding.isNullOrBlank() && !encoding.equals(IDENTITY, ignoreCase = true)) {
            throw mediaFailure("media source returned an encoded body", "파일 서버가 지원하지 않는 압축 형식의 응답을 반환했습니다.")
        }
        val body =
            when (val received = response.body) {
                is MediaResponseBody.Received -> received
                MediaResponseBody.TooLarge -> throw tooLarge()
                MediaResponseBody.Skipped -> throw mediaFailure("media source response body was not read", "파일 응답 본문을 읽지 못했습니다.")
            }
        if (body.bytes.isEmpty()) throw mediaFailure("media source returned no bytes", "파일 서버가 빈 파일을 반환했습니다.")
        return body
    }

    private fun redirectTarget(
        current: URI,
        location: String?,
    ): URI {
        if (location.isNullOrBlank()) throw mediaFailure("media redirect has no location", "파일 서버의 리다이렉트 응답에 이동할 주소가 없습니다.")
        val target =
            try {
                current.resolve(URI(location.trim()))
            } catch (exception: URISyntaxException) {
                throw mediaFailure("media redirect location is invalid", "파일 서버의 리다이렉트 주소가 올바르지 않습니다.", exception)
            }
        if (current.scheme.equals(HTTPS, ignoreCase = true) && !target.scheme.equals(HTTPS, ignoreCase = true)) {
            throw mediaFailure("media redirect must stay on HTTPS", "파일 서버가 HTTPS에서 안전하지 않은 주소로 이동하려고 합니다.")
        }
        return target
    }

    private fun remaining(deadline: Long): Duration {
        val nanos = deadline - System.nanoTime()
        if (nanos <= 0) throw mediaFailure("media download exceeded its deadline", "파일 다운로드가 제한 시간을 초과했습니다.")
        return Duration.ofNanos(nanos)
    }

    private fun responseFailure(
        reason: String,
        statusCode: Int,
    ): MigrationFailureDetail =
        MigrationFailureDetail(
            MigrationFailureStage.MEDIA,
            reason,
            "MediaFetchException",
            httpStatus = statusCode.takeIf { it in httpStatusRange },
        )

    private fun tooLarge(): MediaFetchException =
        MediaFetchException(
            "media exceeds the ${limits.maxBytes} byte limit",
            diagnostic =
                MigrationFailureDetail(
                    MigrationFailureStage.MEDIA,
                    "파일 하나의 크기가 다운로드 허용 한도를 초과했습니다.",
                    "MediaFetchException",
                    limitBytes = limits.maxBytes,
                ),
        )

    companion object {
        private val httpStatusRange = 100..599
        private const val OK = 200
        private const val HTTPS = "https"
        private const val IDENTITY = "identity"
        private val redirectStatuses = setOf(301, 302, 303, 307, 308)
        private val addressCheckThreads = Executor { task -> Thread.ofVirtual().name("media-address-check").start(task) }

        // Notion API 클라이언트와 공유하지 않는다. 쿠키 저장소·인증기·자동 리다이렉트를 두지 않는다.
        // 프록시 선택(nonProxyHosts 포함)과 TLS 신뢰는 플랫폼이 주입하는 JVM 기본 설정을 따른다.
        fun secureClient(connectTimeout: Duration): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build()
    }
}
