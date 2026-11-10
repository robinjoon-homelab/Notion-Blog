package xyz.robinjoon.notionblog.adapter.inbound.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

private const val ADMIN_ROOT = "/admin"

private const val YOUTUBE_VIMEO_FRAME_SOURCES =
    "https://youtube.com https://www.youtube.com https://youtube-nocookie.com " +
        "https://www.youtube-nocookie.com https://vimeo.com https://www.vimeo.com https://player.vimeo.com"

// 공개 글이 Notion의 외부 이미지·미디어와 YouTube·Vimeo 임베드를 쓰므로 img-src·media-src·frame-src만 외부를 연다.
private const val PUBLIC_CONTENT_SECURITY_POLICY =
    "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'; " +
        "script-src 'self'; style-src 'self'; img-src 'self' http: https:; media-src 'self' http: https:; font-src 'self'; " +
        "frame-src $YOUTUBE_VIMEO_FRAME_SOURCES; connect-src 'none'"

// 관리 화면, 키트 화면과 정적 파일, 오류 응답의 기본 정책. 같은 출처의 폼 제출과 fetch만 허용한다.
private const val TEMPLATE_CONTENT_SECURITY_POLICY =
    "default-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; " +
        "script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'"

// 관리자 글 미리보기와 발행 확인은 공개 글과 같은 본문을 그리므로 기본 정책에 본문 소스만 더한다.
private const val POST_PREVIEW_CONTENT_SECURITY_POLICY =
    "default-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; " +
        "script-src 'self'; style-src 'self'; img-src 'self' data: http: https:; media-src 'self' http: https:; font-src 'self'; " +
        "frame-src $YOUTUBE_VIMEO_FRAME_SOURCES; connect-src 'self'"

private val publicRoots = listOf("/posts", "/series", "/media")
private val publicPaths = setOf("/", "/series", "/feed.xml")

// /admin/posts/{id}와 /admin/posts/{id}/review만 해당한다. 분류 편집과 명령 경로는 기본 정책을 쓴다.
private val postPreviewPath = Regex("^/admin/posts/[^/]+(/review)?$")

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
        response.setHeader("Content-Security-Policy", contentSecurityPolicy(path))
        if (admin) {
            // 초안과 관리 화면은 브라우저·프록시 캐시에 남기지 않는다.
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Pragma", "no-cache")
        }
        filterChain.doFilter(request, response)
    }

    private fun contentSecurityPolicy(path: String): String =
        when {
            postPreviewPath.matches(path) -> POST_PREVIEW_CONTENT_SECURITY_POLICY
            isPublicBlogPath(path) -> PUBLIC_CONTENT_SECURITY_POLICY
            else -> TEMPLATE_CONTENT_SECURITY_POLICY
        }

    private fun isPublicBlogPath(path: String): Boolean = path in publicPaths || publicRoots.any { isUnder(path, it) }

    // 보안 설정의 매처와 같이 컨텍스트 경로를 제외한 애플리케이션 내부 경로로 판단한다.
    private fun applicationPath(request: HttpServletRequest): String = request.requestURI.removePrefix(request.contextPath.orEmpty())

    private fun isUnder(
        path: String,
        root: String,
    ): Boolean = path == root || path.startsWith("$root/")
}
