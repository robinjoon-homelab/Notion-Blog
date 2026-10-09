package xyz.robinjoon.notionblog.adapter.inbound.web

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
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY)
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'; " +
                "script-src 'self'; style-src 'self'; img-src 'self' http: https:; media-src 'self' http: https:; font-src 'self'; " +
                "frame-src https://youtube.com https://www.youtube.com https://youtube-nocookie.com " +
                "https://www.youtube-nocookie.com https://vimeo.com https://www.vimeo.com https://player.vimeo.com; " +
                "connect-src 'none'"

        const val PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), microphone=(), payment=(), usb=()"
    }
}
