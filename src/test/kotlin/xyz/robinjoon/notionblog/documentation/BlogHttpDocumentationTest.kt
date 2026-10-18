package xyz.robinjoon.notionblog.documentation

import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.http.MediaType
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.BlogController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.HomePageViewAssembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewAssembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PublicBlogUrls
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.RssFeedController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.RssFeedRenderer
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.RssSummaryExtractor
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebExceptionHandler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebSecurityHeadersFilter
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.PublicFeed
import xyz.robinjoon.notionblog.blog.application.model.PublicFeedLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.model.SitePresentationContext
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicFeedUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssets
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationTokens
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// Runs the real controllers, exception advice, security header filter, view assembly, Thymeleaf templates, and RSS
// renderer on the Spring MVC defaults. The use cases are strict mocks, so publication, scope, and snapshot rules
// stay in the service tests; this test checks how lookup results become documented HTML and RSS responses.
class BlogHttpDocumentationTest {
    private val restDocumentation =
        ManualRestDocumentation(System.getProperty("org.springframework.restdocs.outputDir", "build/generated-snippets"))
    private val postLists = mockk<GetPostListUseCase>()
    private val publicPosts = mockk<GetPublicPostUseCase>()
    private val feeds = mockk<GetPublicFeedUseCase>()
    private val urls = PublicBlogUrls(URI("https://blog.example"))
    private val articleId = PostId(UUID.fromString("5b0f8e2a-3c41-4d6b-9a57-2f1d7c9e8b14"))
    private val pages = PostPageViewAssembler(Clock.fixed(now, ZoneOffset.UTC), urls.feedUrl)
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                BlogController(postLists, publicPosts, pages, HomePageViewAssembler(pages)),
                RssFeedController(feeds, RssFeedRenderer(RssSummaryExtractor()), urls),
            ).setControllerAdvice(WebExceptionHandler())
            .setViewResolvers(thymeleafViewResolver())
            .addFilters<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
            .apply<StandaloneMockMvcBuilder>(
                documentationConfiguration(restDocumentation)
                    .uris()
                    .withScheme("https")
                    .withHost("blog.example")
                    .withPort(443),
            ).build()

    @BeforeEach
    fun startDocumentation(testInfo: TestInfo) {
        restDocumentation.beforeTest(javaClass, testInfo.testMethod.orElseThrow().name)
    }

    @AfterEach
    fun finishDocumentation() {
        restDocumentation.afterTest()
    }

    @Test
    fun `home returns the documented paginated post list`() {
        every { postLists.get(any()) } returns PostListLookupResult.Found(listPage())

        mvc
            .perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("A published article")))
            .andExpect(header().string("Content-Security-Policy", containsString("style-src 'self'")))
            .andDo(document("root-found", htmlHeaders()))
    }

    @Test
    fun `published article returns documented HTML at its internal UUID`() {
        every { publicPosts.get(articleId) } returns PublicPostLookupResult.Found(page(revision(articleId, "A published article")))

        mvc
            .perform(get("/posts/{postId}", articleId.value))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("A published article")))
            .andDo(document("post-found", postIdParameter(), htmlHeaders()))
    }

    @Test
    fun `unpublished article returns documented not found HTML`() {
        every { publicPosts.get(articleId) } returns PublicPostLookupResult.NotFound

        mvc
            .perform(get("/posts/{postId}", articleId.value))
            .andExpect(status().isNotFound)
            .andExpect(content().string(containsString("Page not found")))
            .andDo(document("post-not-found", postIdParameter(), htmlHeaders()))
    }

    @Test
    fun `missing or malformed post identifiers return not found`() {
        val missingId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
        every { publicPosts.get(missingId) } returns PublicPostLookupResult.NotFound

        mvc
            .perform(get("/posts/{postId}", missingId.value))
            .andExpect(status().isNotFound)
            .andDo(document("post-missing", postIdParameter(), htmlHeaders()))
        mvc
            .perform(get("/posts/{postId}", "not-a-uuid"))
            .andExpect(status().isNotFound)
            .andDo(document("post-invalid-id", postIdParameter(), htmlHeaders()))
    }

    @Test
    fun `uninitialized blog documents unavailable HTML for root and article`() {
        val missingId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
        every { postLists.get(any()) } returns PostListLookupResult.ContentUnavailable
        every { publicPosts.get(missingId) } returns PublicPostLookupResult.ContentUnavailable

        mvc
            .perform(get("/"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().string(containsString("Blog temporarily unavailable")))
            .andDo(document("root-unavailable", htmlHeaders()))
        mvc
            .perform(get("/posts/{postId}", missingId.value))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().string(containsString("Blog temporarily unavailable")))
            .andDo(document("post-unavailable", postIdParameter(), htmlHeaders()))
    }

    @Test
    fun `published descendants produce documented RSS with cache validators`() {
        every { feeds.get() } returns PublicFeedLookupResult.Found(feed())

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
        every { feeds.get() } returns PublicFeedLookupResult.Found(feed())
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
        every { feeds.get() } returns PublicFeedLookupResult.ContentUnavailable

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

    private fun chrome(): SitePresentationContext {
        val profile =
            PresentationProfile.restore(
                reference = PresentationProfileRef(PresentationProfileId(UUID.fromString("0f3c3a52-6c55-4f4c-8f43-6f0b3c8e2d11")), 1),
                key = PresentationProfileKey("notion-default"),
                tokens = PresentationTokens(),
                assets = PresentationAssets(styleSheets = listOf(style), scripts = emptyList()),
            )
        return SitePresentationContext(
            site =
                SiteConfiguration.restore(
                    publicationId = PublicationId(UUID.fromString("7d2c9b40-1e8f-4a35-b6d7-0c5e4f3a2b19")),
                    documents = SiteDocuments(root = SourceDocumentRef(SourceId("documentation"), "root"), header = null, footer = null),
                    metadata = metadata,
                    presentationProfile = profile.reference,
                ),
            presentation = profile,
            presentationAssets =
                mapOf(style to PresentationAssetDescriptor("/presentation/notion/v1/notion.css", "text/css", style.integrity)),
            header = null,
            footer = null,
        )
    }

    private fun page(revision: PostRevision) =
        PublicPostPage(chrome(), PublishedPost(revision, now), ContentReferences(emptyMap(), RevisionMedia.empty))

    private fun listPage() =
        PostListPage(
            chrome(),
            ResultPage.of(
                listOf(
                    PostListEntry(
                        articleId,
                        revisionId,
                        PostProperties("A published article", "Published notes", listOf("notes"), null),
                        now,
                    ),
                ),
                PageQuery(1, 10),
            ),
            ContentReferences(emptyMap(), RevisionMedia.empty),
        )

    private fun feed() = PublicFeed(metadata, listOf(PublishedPost(revision(articleId, "A published article"), now)))

    private fun revision(
        id: PostId,
        title: String,
    ) = PostRevision(
        id = revisionId,
        postId = id,
        properties = PostProperties(title, null, emptyList(), null),
        content = BlockTree(listOf(BlockNode(BlockId("paragraph"), TextBlockContent.Paragraph(listOf(InlineContent.Text(title)))))),
        sourceRevision = SourceRevision("revision-1"),
        capturedAt = now,
    )

    // Mirrors the Spring Boot Thymeleaf defaults so the documented responses use the production templates.
    private fun thymeleafViewResolver(): ThymeleafViewResolver {
        val templateResolver =
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                templateMode = TemplateMode.HTML
                characterEncoding = "UTF-8"
            }
        val engine = SpringTemplateEngine()
        engine.setTemplateResolver(templateResolver)
        return ThymeleafViewResolver().apply {
            templateEngine = engine
            characterEncoding = "UTF-8"
            contentType = "text/html;charset=UTF-8"
        }
    }

    private companion object {
        val revisionId = PostRevisionId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
        val now: Instant = Instant.parse("2026-10-08T00:00:00Z")
        val style = PresentationAssetRef("notion-core", 1, "sha384-V763UM2y9iSN6rUXr+H4a3GeowrAJqJ53QPBQoJQ9/W3UVee9kfeg7pO3tJJ7V/T")
        val metadata = SiteMetadata("Documentation blog", "Published notes", "en", null)
    }
}
