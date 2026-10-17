package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

// 주소는 IP 리터럴로만 만들어 실제 DNS를 조회하지 않는다.
class PublicAddressPolicyTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://prod-files-secure.s3.us-west-2.amazonaws.com/ws/file/a.png?X-Amz-Signature=s",
            "HTTPS://PROD-FILES-SECURE.S3.US-WEST-2.AMAZONAWS.COM/ws/file/a.png",
            "https://prod-files-secure.s3.us-west-2.amazonaws.com:443/ws/file/a.png",
            "https://s3.us-west-2.amazonaws.com/secure.notion-static.com/a.png",
        ],
    )
    fun `allows an HTTPS Notion file URL whose host resolves only to public addresses`(url: String) {
        val policy = PublicAddressPolicy(resolver = resolving("52.92.180.10", "2600:1fa0:4000::1"))

        assertThatCode { policy.requireAllowed(URI(url)) }.doesNotThrowAnyException()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://prod-files-secure.s3.us-west-2.amazonaws.com/a.png",
            "ftp://prod-files-secure.s3.us-west-2.amazonaws.com/a.png",
            "/relative/a.png",
            "https://user:password@prod-files-secure.s3.us-west-2.amazonaws.com/a.png",
            "https://prod-files-secure.s3.us-west-2.amazonaws.com:8443/a.png",
            "https://evil.example/a.png",
            "https://prod-files-secure.s3.us-west-2.amazonaws.com.evil.example/a.png",
            "https://evilprod-files-secure.s3.us-west-2.amazonaws.com/a.png",
            "https://bucket.s3.us-west-2.amazonaws.com/a.png",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/a.png",
            "https://127.0.0.1/a.png",
        ],
    )
    fun `rejects a URL that is not an HTTPS default-port allowed host`(url: String) {
        val policy = PublicAddressPolicy(resolver = resolving("52.92.180.10"))

        assertThatThrownBy { policy.requireAllowed(URI(url)) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageNotContaining("password")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.2.1", "198.18.0.1", "::1", "::", "fe80::1", "fc00::1",
            "fd00:ec2::254", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "2001:db8::1",
            "2002:a00:1::1", "2001::1",
        ],
    )
    fun `rejects an allowed host that resolves to a non-public address`(address: String) {
        val policy = PublicAddressPolicy(resolver = resolving(address))

        assertThatThrownBy { policy.requireAllowed(URI(NOTION_FILE_URL)) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageContaining("non-public address")
    }

    @Test
    fun `rejects a host when any resolved address is non-public`() {
        val policy = PublicAddressPolicy(resolver = resolving("52.92.180.10", "10.0.0.5"))

        assertThatThrownBy { policy.requireAllowed(URI(NOTION_FILE_URL)) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageContaining("non-public address")
    }

    @Test
    fun `rejects a host that resolves to no address`() {
        val policy = PublicAddressPolicy(resolver = { emptyList() })

        assertThatThrownBy { policy.requireAllowed(URI(NOTION_FILE_URL)) }.isInstanceOf(MediaFetchException::class.java)
    }

    @Test
    fun `keeps the resolution failure as the cause`() {
        val policy = PublicAddressPolicy(resolver = { host -> throw UnknownHostException(host) })

        assertThatThrownBy { policy.requireAllowed(URI(NOTION_FILE_URL)) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasCauseInstanceOf(UnknownHostException::class.java)
    }

    @Test
    fun `allows a configured host only by exact name`() {
        val policy = PublicAddressPolicy(listOf(" Files.Example. "), resolving("52.92.180.10"))

        assertThatCode { policy.requireAllowed(URI("https://files.example/a")) }.doesNotThrowAnyException()
        assertThatThrownBy { policy.requireAllowed(URI("https://cdn.files.example/a")) }.isInstanceOf(MediaFetchException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "localhost", "10.0.0.1", "[::1]", "*.amazonaws.com", "bad_host.example"])
    fun `rejects a configured host that is not a DNS name`(host: String) {
        assertThatIllegalArgumentException().isThrownBy { PublicAddressPolicy(listOf(host)) }
    }

    @Test
    fun `rejects an empty host list`() {
        assertThatIllegalArgumentException().isThrownBy { PublicAddressPolicy(emptyList()) }
    }

    private fun resolving(vararg addresses: String): MediaHostResolver = MediaHostResolver { addresses.map(InetAddress::getByName) }

    private companion object {
        const val NOTION_FILE_URL = "https://prod-files-secure.s3.us-west-2.amazonaws.com/ws/file/a.png?X-Amz-Signature=s"
    }
}
