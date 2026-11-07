package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionHttpTransport
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.net.Authenticator
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.URI
import java.time.Duration

// 별도 JVM에서만 실행한다. 기본 SSLContext·ProxySelector의 초기화와 전역 인증 상태를 테스트 JVM에서 격리한다.
internal object MediaProxyJvmProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        installAmbientCredentials()
        val url = URI(args[0])
        val expectation = args[1]
        val limits = MediaFetchLimits(args[3].toLong(), Duration.ofMillis(args[2].toLong()), 3)
        if (args[4] == "notion") {
            val transport = NotionHttpTransport("https://api.notion.com/v1", "test-notion-secret", Duration.ofSeconds(5))
            check(transport.get("/file", "unused").get("ok").asBoolean())
        }
        HttpMediaGateway(limits, addressPolicy(url)).use { gateway ->
            val result = runCatching { gateway.fetch(MediaSource.SourceHosted(url, null)) }
            if (expectation.startsWith("ok:")) {
                check(result.getOrThrow().bytes.contentEquals(expectation.removePrefix("ok:").toByteArray()))
            } else {
                val failure = checkNotNull(result.exceptionOrNull()) { "Expected media fetch to fail" }
                check(failure is MediaFetchException) { "Expected a media failure: $failure" }
                val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
                val expected = expectation.removePrefix("fail:")
                check(causes.any { it.javaClass.simpleName.contains(expected) || it.message.orEmpty().contains(expected) }) {
                    "Unexpected failure: $causes"
                }
                check(causes.none { it.message.orEmpty().contains("secret-signature") })
            }
        }
    }

    private fun addressPolicy(url: URI): MediaAddressPolicy {
        if (url.host == "localhost" || url.host == "127.0.0.1") {
            return MediaAddressPolicy { target -> check(target.scheme == "https" && target.authority == url.authority) }
        }
        // 보안 정책의 URL·포트·공인 주소 검사는 그대로 실행하되 외부 DNS는 사용하지 않는다.
        return PublicAddressPolicy(
            listOf("first.media.test", "second.media.test", "private.media.test"),
            MediaHostResolver { host ->
                val bytes = if (host == "private.media.test") byteArrayOf(127, 0, 0, 1) else byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
                listOf(InetAddress.getByAddress(bytes))
            },
        )
    }

    private fun installAmbientCredentials() {
        val cookies = CookieManager()
        cookies.cookieStore.add(URI("https://first.media.test"), HttpCookie("session", "secret-cookie"))
        cookies.cookieStore.add(URI("https://second.media.test"), HttpCookie("session", "secret-cookie"))
        CookieHandler.setDefault(cookies)
        Authenticator.setDefault(
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication("ambient-user", "ambient-password".toCharArray())
            },
        )
    }
}
