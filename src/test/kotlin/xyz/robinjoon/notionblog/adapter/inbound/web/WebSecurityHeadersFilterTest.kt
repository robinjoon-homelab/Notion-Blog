package xyz.robinjoon.notionblog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

private const val CSP = "Content-Security-Policy"
private const val FRAME_SOURCES =
    "https://youtube.com https://www.youtube.com https://youtube-nocookie.com " +
        "https://www.youtube-nocookie.com https://vimeo.com https://www.vimeo.com https://player.vimeo.com"
private const val PUBLIC_CSP =
    "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'; " +
        "script-src 'self'; style-src 'self'; img-src 'self' http: https:; media-src 'self' http: https:; font-src 'self'; " +
        "frame-src $FRAME_SOURCES; connect-src 'none'"
private const val TEMPLATE_CSP =
    "default-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; " +
        "script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'"
private const val PREVIEW_CSP =
    "default-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; " +
        "script-src 'self'; style-src 'self'; img-src 'self' data: http: https:; media-src 'self' http: https:; font-src 'self'; " +
        "frame-src $FRAME_SOURCES; connect-src 'self'"
private const val NOSNIFF = "X-Content-Type-Options"
private const val FRAME_OPTIONS = "X-Frame-Options"
private const val REFERRER_POLICY = "Referrer-Policy"
private const val PERMISSIONS_POLICY = "Permissions-Policy"
private const val EXPECTED_PERMISSIONS_POLICY =
    "accelerometer=(), camera=(), geolocation=(), microphone=(), payment=(), usb=()"
private const val CACHE_CONTROL = "Cache-Control"
private const val PRAGMA = "Pragma"

@RestController
private class AnyPathController {
    @GetMapping("/unavailable")
    fun unavailable(): Nothing = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)

    @GetMapping("/**")
    fun any(): String = "ok"
}

class WebSecurityHeadersFilterTest {
    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(AnyPathController())
            .addFilters<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
            .build()

    @ParameterizedTest
    @ValueSource(strings = ["/", "/posts/x", "/series", "/series/x", "/feed.xml", "/media/x"])
    fun `public blog pages keep the public policy and stay cacheable`(path: String) {
        response(path).andExpectBrowserHeaders(PUBLIC_CSP).andExpectCacheable()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/admin/posts/x", "/admin/posts/x/review"])
    fun `admin preview and review add only the post content sources and are never stored`(path: String) {
        response(path).andExpectBrowserHeaders(PREVIEW_CSP).andExpectNeverStored()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/admin", "/admin/login", "/admin/posts", "/admin/posts/x/categories", "/admin/posts/x/publish"])
    fun `other admin responses get the template policy and are never stored`(path: String) {
        response(path).andExpectBrowserHeaders(TEMPLATE_CSP).andExpectNeverStored()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/ui/tokens.css",
            "/app/app.css",
            "/presentation/notion/v1/notion.css",
            "/docs/index.html",
            "/actuator/health",
            "/unknown",
            "/postsx",
            "/seriesx",
            "/administrator",
        ],
    )
    fun `every other path gets the template policy and stays cacheable`(path: String) {
        response(path).andExpectBrowserHeaders(TEMPLATE_CSP).andExpectCacheable()
    }

    @Test
    fun `no policy has an inline or eval allowance`() {
        listOf("/", "/admin", "/admin/posts/x").forEach {
            assertThat(response(it).andReturn().response.getHeader(CSP)).doesNotContain("unsafe-inline", "unsafe-eval")
        }
    }

    @Test
    fun `an error response has the same protective headers`() {
        response("/unavailable")
            .andExpect(status().isServiceUnavailable)
            .andExpectBrowserHeaders(TEMPLATE_CSP)
            .andExpectCacheable()
    }

    @Test
    fun `paths are judged without the context path`() {
        mockMvc
            .perform(get("/blog/admin/posts/x").contextPath("/blog"))
            .andExpect(header().string(CSP, PREVIEW_CSP))
            .andExpect(header().string(CACHE_CONTROL, "no-store"))
        mockMvc
            .perform(get("/blog/posts/x").contextPath("/blog"))
            .andExpect(header().string(CSP, PUBLIC_CSP))
    }

    @Test
    fun `an admin segment inside the context path does not make the path admin`() {
        mockMvc
            .perform(get("/admin/posts").contextPath("/admin"))
            .andExpect(header().string(CSP, PUBLIC_CSP))
            .andExpect(header().doesNotExist(CACHE_CONTROL))
    }

    private fun response(path: String): ResultActions = mockMvc.perform(get(path))

    private fun ResultActions.andExpectBrowserHeaders(contentSecurityPolicy: String): ResultActions =
        andExpect(header().string(CSP, contentSecurityPolicy))
            .andExpect(header().string(NOSNIFF, "nosniff"))
            .andExpect(header().string(FRAME_OPTIONS, "DENY"))
            .andExpect(header().string(REFERRER_POLICY, "no-referrer"))
            .andExpect(header().string(PERMISSIONS_POLICY, EXPECTED_PERMISSIONS_POLICY))

    private fun ResultActions.andExpectNeverStored(): ResultActions =
        andExpect(header().string(CACHE_CONTROL, "no-store")).andExpect(header().string(PRAGMA, "no-cache"))

    private fun ResultActions.andExpectCacheable(): ResultActions =
        andExpect(header().doesNotExist(CACHE_CONTROL)).andExpect(header().doesNotExist(PRAGMA))
}
