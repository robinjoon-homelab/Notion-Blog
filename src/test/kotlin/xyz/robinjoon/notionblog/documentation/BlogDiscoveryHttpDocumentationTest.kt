package xyz.robinjoon.notionblog.documentation

import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.BlogController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.HomePageViewAssembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewAssembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.SeriesController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.SeriesPageViewAssembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebExceptionHandler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebFixtures
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebSecurityHeadersFilter
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.HomePageQuery
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.application.model.PublicCategoryCount
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeries
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesSummary
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicSeriesUseCase
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// 태그·검색·시리즈 탐색 경로를 실제 컨트롤러, 화면 조립, Thymeleaf 템플릿으로 문서화한다. 입력 포트는 엄격한 목이다.
// 공개 규칙(공개본만 검색·계산)은 서비스 테스트의 몫이고, 이 테스트는 조회 결과가 HTML 응답이 되는 모습을 확인한다.
class BlogDiscoveryHttpDocumentationTest {
    private val restDocumentation =
        ManualRestDocumentation(System.getProperty("org.springframework.restdocs.outputDir", "build/generated-snippets"))
    private val postLists = mockk<GetPostListUseCase>()
    private val publicPosts = mockk<GetPublicPostUseCase>()
    private val publicSeries = mockk<GetPublicSeriesUseCase>()
    private val pages = PostPageViewAssembler(Clock.fixed(now, ZoneOffset.UTC))
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                BlogController(postLists, publicPosts, pages, HomePageViewAssembler(pages)),
                SeriesController(publicSeries, SeriesPageViewAssembler(pages)),
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
    fun `tag filter documents the filtered home, the legacy category address and the unknown tag`() {
        val category = CategoryId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
        val missing = CategoryId(UUID.fromString("00000000-0000-0000-0000-0000000000c2"))
        val listed = ListedCategory(category, "Kotlin")
        val base = listPage()
        val filtered =
            base.copy(
                entries = base.entries.copy(items = base.entries.items.map { it.copy(categories = listOf(listed)) }),
                configuration = HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.NEWEST_FIRST, 10, HomePostDetails()),
                categories = listOf(PublicCategoryCount(listed, 1)),
                selectedCategoryId = category,
                totalPublished = 3,
            )
        every { postLists.get(HomePageQuery(1, category)) } returns PostListLookupResult.Found(filtered)
        every { postLists.get(HomePageQuery(1, missing)) } returns PostListLookupResult.NotFound

        mvc
            .perform(get("/?tag={tag}", category.value.toString()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("aria-current=\"true\"")))
            .andExpect(content().string(containsString("/ui/default-post-thumbnail.svg")))
            .andExpect(content().string(containsString("nb-home-cards")))
            .andDo(document("root-tag", homeQueryParameters(), htmlHeaders()))
        mvc
            .perform(get("/?category={category}", category.value.toString()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("/?tag=${category.value}")))
            .andDo(document("root-category-alias", homeQueryParameters(), htmlHeaders()))
        mvc
            .perform(get("/?tag={tag}", missing.value.toString()))
            .andExpect(status().isNotFound)
            .andDo(document("root-tag-missing", homeQueryParameters(), htmlHeaders()))
    }

    @Test
    fun `search documents the result list, the empty result and the rejected long text`() {
        val tooLong = "가".repeat(HomePageQuery.MAX_SEARCH_LENGTH + 1)
        every { postLists.get(HomePageQuery(1, null, "published")) } returns
            PostListLookupResult.Found(listPage().copy(search = "published"))
        every { postLists.get(HomePageQuery(1, null, "nothing")) } returns
            PostListLookupResult.Found(listPage().copy(entries = ResultPage.of(emptyList(), PageQuery(1, 10)), search = "nothing"))
        every { postLists.get(HomePageQuery(1)) } returns PostListLookupResult.Found(listPage())

        mvc
            .perform(get("/?q={q}", " published "))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("검색 결과 1개")))
            .andExpect(content().string(containsString("A published article")))
            .andDo(document("root-search", homeQueryParameters(), htmlHeaders()))
        mvc
            .perform(get("/?q={q}", "nothing"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("조건에 맞는 공개 글이 없습니다.")))
            .andDo(document("root-search-empty", homeQueryParameters(), htmlHeaders()))
        mvc
            .perform(get("/?q={q}", tooLong))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("검색어는 100자 이하로 입력하세요.")))
            .andDo(document("root-search-too-long", homeQueryParameters(), htmlHeaders()))
    }

    @Test
    fun `series list documents cards, the empty list and the unavailable blog`() {
        val seriesId = WebFixtures.seriesId
        every { publicSeries.list() } returns
            PublicSeriesListLookupResult.Found(
                PublicSeriesListPage(
                    WebFixtures.chrome(),
                    listOf(PublicSeriesSummary(seriesId, "DDD 적용기", "차근차근 적용합니다", 2, listEntry())),
                    ContentReferences(emptyMap(), RevisionMedia.empty),
                ),
            )

        mvc
            .perform(get("/series"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("DDD 적용기")))
            .andExpect(content().string(containsString("글 2개")))
            .andDo(document("series-list-found", htmlHeaders()))

        every { publicSeries.list() } returns
            PublicSeriesListLookupResult.Found(
                PublicSeriesListPage(WebFixtures.chrome(), emptyList(), ContentReferences(emptyMap(), RevisionMedia.empty)),
            )
        mvc
            .perform(get("/series"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("아직 공개된 시리즈가 없습니다.")))
            .andDo(document("series-list-empty", htmlHeaders()))

        every { publicSeries.list() } returns PublicSeriesListLookupResult.ContentUnavailable
        mvc.perform(get("/series")).andExpect(status().isServiceUnavailable).andDo(document("series-list-unavailable", htmlHeaders()))
    }

    @Test
    fun `series detail documents the ordered public posts and the not found and unavailable answers`() {
        val seriesId = WebFixtures.seriesId
        val secondProperties = PostProperties("Second chapter", null, emptyList(), null)
        val second = listEntry().copy(postId = WebFixtures.seriesPostId(1), properties = secondProperties)
        val posts = listOf(listEntry(), second)
        every { publicSeries.get(seriesId) } returns
            PublicSeriesLookupResult.Found(
                PublicSeriesPage(
                    WebFixtures.chrome(),
                    PublicSeries(seriesId, "DDD 적용기", "차근차근 적용합니다", posts),
                    ContentReferences(emptyMap(), RevisionMedia.empty),
                    HomeConfiguration.defaults(),
                ),
            )

        mvc
            .perform(get("/series/{seriesId}", seriesId.value.toString()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Second chapter")))
            .andDo(document("series-found", seriesIdParameter(), htmlHeaders()))

        every { publicSeries.get(seriesId) } returns PublicSeriesLookupResult.NotFound
        mvc
            .perform(get("/series/{seriesId}", seriesId.value.toString()))
            .andExpect(status().isNotFound)
            .andDo(document("series-not-found", seriesIdParameter(), htmlHeaders()))
        mvc
            .perform(get("/series/{seriesId}", "not-a-uuid"))
            .andExpect(status().isNotFound)
            .andDo(document("series-invalid-id", seriesIdParameter(), htmlHeaders()))

        every { publicSeries.get(seriesId) } returns PublicSeriesLookupResult.ContentUnavailable
        mvc
            .perform(get("/series/{seriesId}", seriesId.value.toString()))
            .andExpect(status().isServiceUnavailable)
            .andDo(document("series-unavailable", seriesIdParameter(), htmlHeaders()))
    }

    @Test
    fun `article in a series documents the contents list with previous and next links and app tags`() {
        val tag = ListedCategory(CategoryId(UUID.fromString("00000000-0000-0000-0000-0000000000c1")), "Kotlin")
        every { publicPosts.get(WebFixtures.postId) } returns
            PublicPostLookupResult.Found(
                WebFixtures
                    .publicPage(WebFixtures.revision(title = "A published article"))
                    .copy(categories = listOf(tag), series = WebFixtures.seriesNavigation(currentIndex = 1)),
            )

        mvc
            .perform(get("/posts/{postId}", WebFixtures.postId.value))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("nb-series-toc")))
            .andExpect(content().string(containsString("nb-series-pager")))
            .andExpect(content().string(containsString("/?tag=${tag.id.value}")))
            .andDo(document("post-series", postIdParameter(), htmlHeaders()))
    }

    private fun homeQueryParameters() =
        queryParameters(
            parameterWithName("page").description("쪽 번호, 1 미만은 1쪽").optional(),
            parameterWithName("tag").description("태그 UUID. 선택한 태그와 검색어를 함께 적용합니다").optional(),
            parameterWithName("category").description("옛 태그 필터 주소. tag와 같은 응답이며 둘 다 있으면 tag를 따릅니다").optional(),
            parameterWithName("q").description("공개 글의 제목·요약 검색어. 앞뒤 공백은 지우며 100자를 넘으면 자르지 않고 400으로 안내합니다").optional(),
        )

    private fun seriesIdParameter() = pathParameters(parameterWithName("seriesId").description("시리즈 UUID"))

    private fun postIdParameter() = pathParameters(parameterWithName("postId").description("블로그 내부 게시글 UUID, Notion 페이지 ID가 아닙니다"))

    private fun htmlHeaders() =
        responseHeaders(
            headerWithName("Content-Type").description("서버에서 렌더링한 HTML"),
            headerWithName("Content-Security-Policy").description("동일 출처의 스크립트·스타일만 허용하는 보안 정책"),
        )

    private fun listEntry() =
        PostListEntry(
            WebFixtures.seriesPostId(0),
            WebFixtures.revisionId,
            PostProperties("First chapter", "Chapter notes", emptyList(), null),
            now,
        )

    private fun listPage() =
        PostListPage(
            WebFixtures.chrome(),
            ResultPage.of(
                listOf(
                    PostListEntry(
                        WebFixtures.postId,
                        WebFixtures.revisionId,
                        PostProperties("A published article", "Published notes", emptyList(), null),
                        now,
                    ),
                ),
                PageQuery(1, 10),
            ),
            ContentReferences(emptyMap(), RevisionMedia.empty),
        )

    // Spring Boot의 Thymeleaf 기본값을 그대로 따라 문서화한 응답이 운영 템플릿을 쓰게 한다.
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
        val now: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
