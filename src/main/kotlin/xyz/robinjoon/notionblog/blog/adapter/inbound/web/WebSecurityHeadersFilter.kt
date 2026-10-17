package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

class WebSecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // 보안 설정의 /admin/** 매처와 같이 컨텍스트 경로를 제외한 애플리케이션 내부 경로로 판단한다.
        val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
        val admin = path == "/admin" || path.startsWith("/admin/")
        val contentSecurityPolicy = if (admin) adminContentSecurityPolicy else PUBLIC_CONTENT_SECURITY_POLICY
        response.setHeader("Content-Security-Policy", contentSecurityPolicy)
        if (admin) {
            // 초안과 관리 화면은 브라우저·프록시 캐시에 남기지 않는다.
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Pragma", "no-cache")
        }
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY)
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val PUBLIC_CONTENT_SECURITY_POLICY =
            "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'; " +
                "script-src 'self'; style-src 'self'; img-src 'self' http: https:; media-src 'self' http: https:; font-src 'self'; " +
                "frame-src https://youtube.com https://www.youtube.com https://youtube-nocookie.com " +
                "https://www.youtube-nocookie.com https://vimeo.com https://www.vimeo.com https://player.vimeo.com; " +
                "connect-src 'none'"

        // 관리 화면의 폼만 같은 출처로 제출할 수 있다.
        val adminContentSecurityPolicy = PUBLIC_CONTENT_SECURITY_POLICY.replace("form-action 'none'", "form-action 'self'")

        const val PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), microphone=(), payment=(), usb=()"
    }
}
