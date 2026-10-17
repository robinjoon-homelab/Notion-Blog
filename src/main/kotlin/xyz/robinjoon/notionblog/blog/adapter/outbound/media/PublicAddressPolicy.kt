package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale

// 연결 전에 URL 한 hop을 검사한다. 허용하지 않으면 MediaFetchException을 던진다.
internal fun interface MediaAddressPolicy {
    fun requireAllowed(url: URI)
}

internal fun interface MediaHostResolver {
    fun resolve(host: String): List<InetAddress>
}

// HTTPS 기본 포트의 Notion 파일 저장소 호스트만 허용하고, 해석된 주소가 모두 공인 유니캐스트일 때만 연결한다.
// JDK HttpClient는 연결할 주소를 지정할 수 없어 검사와 연결 사이의 DNS 재바인딩을 주소 고정으로 막지 못한다.
// 그래서 공격자가 DNS를 바꿀 수 없는 고정 호스트 목록을 1차 방어로 두고, 주소 검사는 내부망 해석·잘못된 목록을 막는다.
// 시스템 resolver를 쓰므로 HTTP 클라이언트도 직후 같은 JVM 주소 캐시를 읽는다.
internal class PublicAddressPolicy(
    allowedHosts: Collection<String> = notionFileHosts,
    private val resolver: MediaHostResolver = MediaHostResolver { host -> InetAddress.getAllByName(host).toList() },
) : MediaAddressPolicy {
    private val hosts: Set<String> = allowedHosts.map(::configuredHost).toSet()

    init {
        require(hosts.isNotEmpty()) { "media source hosts must not be empty" }
    }

    override fun requireAllowed(url: URI) {
        val host = allowedHost(url)
        val addresses =
            try {
                resolver.resolve(host)
            } catch (exception: UnknownHostException) {
                throw MediaFetchException("media source host could not be resolved", exception)
            }
        if (addresses.isEmpty() || !addresses.all(PublicUnicastAddresses::contains)) {
            throw MediaFetchException("media source host resolves to a non-public address")
        }
    }

    private fun allowedHost(url: URI): String {
        if (!url.isAbsolute || !url.scheme.equals(HTTPS, ignoreCase = true)) {
            throw MediaFetchException("media URL must use HTTPS")
        }
        if (url.rawUserInfo != null || (url.port != DEFAULT_PORT && url.port != HTTPS_PORT)) {
            throw MediaFetchException("media URL must not carry user info or a non-default port")
        }
        val host = url.host?.let(::normalize)
        if (host == null || host !in hosts) {
            throw MediaFetchException("media URL host is not an allowed media source host")
        }
        return host
    }

    private fun configuredHost(value: String): String {
        val host = normalize(value.trim())
        require(dnsNamePattern.matches(host) && !host.all { it.isDigit() || it == '.' }) {
            "media source hosts must be DNS host names"
        }
        return host
    }

    private fun normalize(host: String): String = host.lowercase(Locale.ROOT).removeSuffix(".")

    companion object {
        // Notion API가 돌려주는 업로드 파일의 서명 URL 호스트다. https://developers.notion.com/reference/file-object
        val notionFileHosts: Set<String> =
            setOf(
                "prod-files-secure.s3.us-west-2.amazonaws.com",
                "s3.us-west-2.amazonaws.com",
                "s3-us-west-2.amazonaws.com",
            )

        private const val HTTPS = "https"
        private const val DEFAULT_PORT = -1
        private const val HTTPS_PORT = 443
        private val dnsNamePattern = Regex("(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")
    }
}
