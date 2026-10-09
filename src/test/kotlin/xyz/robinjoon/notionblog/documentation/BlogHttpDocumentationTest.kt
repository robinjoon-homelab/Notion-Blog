package xyz.robinjoon.notionblog.documentation

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest(
    properties = [
        "notion.token=test-token",
        "notion.settings-data-source-id=test-settings",
        "blog.public-base-url=https://blog.example",
        "blog.synchronization.enabled=false",
    ],
)
@AutoConfigureMockMvc
@AutoConfigureRestDocs(uriScheme = "https", uriHost = "blog.example", uriPort = 443)
@Import(DocumentedBlogFixture::class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BlogHttpDocumentationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val blog: DocumentedBlogFixture,
) {
    @BeforeEach
    fun clearContent() {
        blog.clearContent()
    }

    @Test
    fun `published root returns documented HTML`() {
        blog.publishBlog()

        mvc
            .perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("Documentation blog")))
            .andExpect(header().string("Content-Security-Policy", containsString("style-src 'self'")))
            .andDo(document("root-found", htmlHeaders()))
    }

    @Test
    fun `published article returns documented HTML at its internal UUID`() {
        val articleId = blog.publishBlog()

        mvc
            .perform(get("/posts/{postId}", articleId.value))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("A published article")))
            .andDo(document("post-found", postIdParameter(), htmlHeaders()))
    }

    @Test
    fun `unpublished root returns documented not found HTML`() {
        blog.publishBlog()
        blog.unpublishRoot()

        mvc
            .perform(get("/"))
            .andExpect(status().isNotFound)
            .andExpect(content().string(containsString("Page not found")))
            .andDo(document("root-not-found", htmlHeaders()))
    }

    @Test
    fun `unpublished article returns documented not found HTML`() {
        val articleId = blog.publishBlog()
        blog.unpublishArticle()

        mvc
            .perform(get("/posts/{postId}", articleId.value))
            .andExpect(status().isNotFound)
            .andExpect(content().string(containsString("Page not found")))
            .andDo(document("post-not-found", postIdParameter(), htmlHeaders()))
    }

    @Test
    fun `missing or malformed post identifiers return not found`() {
        blog.publishBlog()

        mvc
            .perform(get("/posts/{postId}", UUID.fromString("00000000-0000-0000-0000-000000000000")))
            .andExpect(status().isNotFound)
            .andDo(document("post-missing", postIdParameter(), htmlHeaders()))
        mvc
            .perform(get("/posts/{postId}", "not-a-uuid"))
            .andExpect(status().isNotFound)
            .andDo(document("post-invalid-id", postIdParameter(), htmlHeaders()))
    }

    @Test
    fun `uninitialized blog documents unavailable HTML for root and article`() {
        mvc
            .perform(get("/"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().string(containsString("Blog temporarily unavailable")))
            .andDo(document("root-unavailable", htmlHeaders()))
        mvc
            .perform(get("/posts/{postId}", UUID.fromString("00000000-0000-0000-0000-000000000000")))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().string(containsString("Blog temporarily unavailable")))
            .andDo(document("post-unavailable", postIdParameter(), htmlHeaders()))
    }

    @Test
    fun `published descendants produce documented RSS with cache validators`() {
        blog.publishBlog()

        mvc
            .perform(get("/feed.xml"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/rss+xml"))
            .andExpect(xpath("/rss/@version").string("2.0"))
            .andExpect(xpath("/rss/channel/item").nodeCount(1))
            .andExpect(xpath("/rss/channel/item/title").string("A published article"))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(header().exists("ETag"))
            .andDo(document("feed-found", feedHeaders().and(headerWithName("Content-Type").description("RSS 2.0 XML, UTF-8"))))
    }

    @Test
    fun `unchanged RSS returns documented not modified without a response body`() {
        blog.publishBlog()
        val response =
            mvc
                .perform(get("/feed.xml"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val etag = requireNotNull(response.getHeader("ETag"))

        mvc
            .perform(get("/feed.xml").header("If-None-Match", etag))
            .andExpect(status().isNotModified)
            .andExpect(content().bytes(byteArrayOf()))
            .andExpect(header().string("ETag", etag))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andDo(
                document(
                    "feed-not-modified",
                    requestHeaders(headerWithName("If-None-Match").description("이전에 받은 ETag")),
                    feedHeaders(),
                ),
            )
    }

    @Test
    fun `uninitialized RSS remains unavailable even with a wildcard validator`() {
        mvc
            .perform(get("/feed.xml").header("If-None-Match", "*"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(content().bytes(byteArrayOf()))
            .andDo(
                document(
                    "feed-unavailable",
                    requestHeaders(headerWithName("If-None-Match").description("조건부 요청도 수집 준비 상태를 먼저 확인합니다")),
                    responseHeaders(headerWithName("Cache-Control").description("실패 응답은 저장하지 않습니다")),
                ),
            )
    }

    private fun postIdParameter() = pathParameters(parameterWithName("postId").description("블로그 내부 게시글 UUID, Notion 페이지 ID가 아닙니다"))

    private fun htmlHeaders() =
        responseHeaders(
            headerWithName("Content-Type").description("서버에서 렌더링한 HTML"),
            headerWithName("Content-Security-Policy").description("동일 출처의 스크립트·스타일만 허용하는 보안 정책"),
        )

    private fun feedHeaders() =
        responseHeaders(
            headerWithName("Cache-Control").description("no-cache: 저장된 응답을 사용하기 전에 서버에서 다시 확인합니다"),
            headerWithName("ETag").description("피드 본문이 같으면 유지되는 캐시 검증값"),
        )

    companion object {
        @Container
        @JvmStatic
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", container::getJdbcUrl)
            registry.add("spring.datasource.username", container::getUsername)
            registry.add("spring.datasource.password", container::getPassword)
        }
    }
}
