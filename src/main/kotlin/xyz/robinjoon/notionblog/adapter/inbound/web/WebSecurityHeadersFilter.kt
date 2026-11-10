package xyz.robinjoon.notionblog.adapter.inbound.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

private const val ADMIN_ROOT = "/admin"

// 공개 글이 Notion의 외부 이미지·미디어와 YouTube·Vimeo 임베드를 쓰므로 img-src·media-src·frame-src만 외부를 연다.
private const val PUBLIC_CONTENT_SECURITY_POLICY =
    "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'; " +
        "script-src 'self'; style-src 'self'; img-src 'self' http: https:; media-src 'self' http: https:; font-src 'self'; " +
        "frame-src https://youtube.com https://www.youtube.com https://youtube-nocookie.com " +
        "https://www.youtube-nocookie.com https://vimeo.com https://www.vimeo.com https://player.vimeo.com; " +
        "connect-src 'none'"

// 관리 화면의 폼만 같은 출처로 제출할 수 있다.
private val adminContentSecurityPolicy = PUBLIC_CONTENT_SECURITY_POLICY.replace("form-action 'none'", "form-action 'self'")

private val browserHeaders =
    mapOf(
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Permissions-Policy" to "accelerometer=(), camera=(), geolocation=(), microphone=(), payment=(), usb=()",
    )

class WebSecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = applicationPath(request)
        val admin = isUnder(path, ADMIN_ROOT)
        browserHeaders.forEach(response::setHeader)
        response.setHeader("Content-Security-Policy", if (admin) adminContentSecurityPolicy else PUBLIC_CONTENT_SECURITY_POLICY)
        if (admin) {
            // 초안과 관리 화면은 브라우저·프록시 캐시에 남기지 않는다.
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Pragma", "no-cache")
        }
        filterChain.doFilter(request, response)
    }

    // 보안 설정의 매처와 같이 컨텍스트 경로를 제외한 애플리케이션 내부 경로로 판단한다.
    private fun applicationPath(request: HttpServletRequest): String = request.requestURI.removePrefix(request.contextPath.orEmpty())

    private fun isUnder(
        path: String,
        root: String,
    ): Boolean = path == root || path.startsWith("$root/")
}
