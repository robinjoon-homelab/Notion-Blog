package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

// 사설망·loopback·any-local·link-local(169.254/16 클라우드 메타데이터 포함)·CGNAT·ULA·멀티캐스트·예약 대역을 공인 주소로 보지 않는다.
internal object PublicUnicastAddresses {
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val IPV4_BITS = 32
    private const val IPV4_ALL_BITS = 0xFFFF_FFFFL
    private const val IPV6_GLOBAL_MASK = 0xE0
    private const val IPV6_GLOBAL_PREFIX = 0x20
    private const val IPV6_IETF_THIRD_LIMIT = 0x02

    // RFC 6890 특수 목적 대역과 멀티캐스트·예약 대역.
    private val blockedIpv4Ranges =
        listOf(
            Ipv4Range("0.0.0.0", 8),
            Ipv4Range("10.0.0.0", 8),
            Ipv4Range("100.64.0.0", 10),
            Ipv4Range("127.0.0.0", 8),
            Ipv4Range("169.254.0.0", 16),
            Ipv4Range("172.16.0.0", 12),
            Ipv4Range("192.0.0.0", 24),
            Ipv4Range("192.0.2.0", 24),
            Ipv4Range("192.88.99.0", 24),
            Ipv4Range("192.168.0.0", 16),
            Ipv4Range("198.18.0.0", 15),
            Ipv4Range("198.51.100.0", 24),
            Ipv4Range("203.0.113.0", 24),
            Ipv4Range("224.0.0.0", 4),
            Ipv4Range("240.0.0.0", 4),
        )

    fun contains(address: InetAddress): Boolean =
        when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val value = bytes.fold(0L) { acc, byte -> (acc shl BITS_PER_BYTE) or (byte.toLong() and BYTE_MASK.toLong()) }
        return blockedIpv4Ranges.none { range -> range.contains(value) }
    }

    // 전역 유니캐스트 2000::/3만 허용하고 그 안의 IETF 프로토콜(Teredo 포함, 2001::/23)·문서(2001:db8::/32)·6to4(2002::/16)를 막는다.
    // ::1, ::, IPv4-mapped·IPv4-compatible·NAT64, fc00::/7, fe80::/10, ff00::/8은 2000::/3 밖이므로 막힌다.
    // JDK는 IPv4-mapped 주소를 Inet4Address로 바꾸므로 그 경우 IPv4 규칙을 탄다.
    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        val (first, second, third, fourth) = bytes.take(4).map { it.toInt() and BYTE_MASK }
        val isGlobalUnicast = first and IPV6_GLOBAL_MASK == IPV6_GLOBAL_PREFIX
        val isIetfProtocol = first == 0x20 && second == 0x01 && third < IPV6_IETF_THIRD_LIMIT
        val isDocumentation = first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8
        val isSixToFour = first == 0x20 && second == 0x02
        return isGlobalUnicast && !isIetfProtocol && !isDocumentation && !isSixToFour
    }

    private class Ipv4Range(
        network: String,
        prefixLength: Int,
    ) {
        private val mask = (IPV4_ALL_BITS shl (IPV4_BITS - prefixLength)) and IPV4_ALL_BITS
        private val start = network.split('.').fold(0L) { acc, part -> (acc shl BITS_PER_BYTE) or part.toLong() } and mask

        fun contains(value: Long): Boolean = value and mask == start
    }
}
