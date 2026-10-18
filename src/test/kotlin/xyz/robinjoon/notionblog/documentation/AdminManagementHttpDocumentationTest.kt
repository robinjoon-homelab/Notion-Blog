package xyz.robinjoon.notionblog.documentation

import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminCategoryController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminHomeController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminPostCategoryController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminSeriesController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebExceptionHandler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebFixtures
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebSecurityHeadersFilter
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.CategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.application.model.PostClassification
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesChangeResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesEditor
import xyz.robinjoon.notionblog.blog.application.model.SeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostSummary
import xyz.robinjoon.notionblog.blog.application.model.SourceTagCandidate
import xyz.robinjoon.notionblog.blog.application.port.input.CategoryManagementUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.HomeConfigurationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.PostClassificationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SeriesManagementUseCase
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import java.util.UUID

// 설정·태그·분류 편집·시리즈 관리 경로를 실제 컨트롤러와 Thymeleaf 템플릿으로 문서화한다. 입력 포트는 엄격한 목이다.
// 인증과 CSRF는 보안 필터 체인의 책임이므로 요청이 통과한 뒤 컨트롤러가 답하는 내용만 문서화한다.
class AdminManagementHttpDocumentationTest {
    private val restDocumentation =
        ManualRestDocumentation(System.getProperty("org.springframework.restdocs.outputDir", "build/generated-snippets"))
    private val homeConfigurations = mockk<HomeConfigurationUseCase>()
    private val categories = mockk<CategoryManagementUseCase>()
    private val classifications = mockk<PostClassificationUseCase>()
    private val seriesManagement = mockk<SeriesManagementUseCase>()
    private val postId = WebFixtures.postId
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AdminHomeController(homeConfigurations),
                AdminCategoryController(categories),
                AdminPostCategoryController(classifications),
                AdminSeriesController(seriesManagement),
            ).setControllerAdvice(WebExceptionHandler())
            .setViewResolvers(thymeleafViewResolver())
            .addFilters<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
            .apply<StandaloneMockMvcBuilder>(
                documentationConfiguration(restDocumentation)
                    .uris()
                    .withScheme("https")
                    .withHost("blog.example")
                    .withPort(HTTPS_PORT),
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
    fun `home editor documents the saved settings and the rejected form`() {
        every { homeConfigurations.get() } returns HomeConfiguration.defaults()
        every { homeConfigurations.update(any()) } returns Unit

        mvc
            .perform(get("/admin/home"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("예시")))
            .andDo(document("admin-home"))
        mvc
            .perform(
                post(
                    "/admin/home",
                ).param("layout", "CARDS").param("order", "OLDEST_FIRST").param("pageSize", "12").param("showDate", "true"),
            ).andExpect(status().isSeeOther)
            .andExpect(redirectedUrl("/admin/home"))
            .andDo(document("admin-home-save"))
        mvc
            .perform(post("/admin/home").param("layout", "CARDS").param("order", "OLDEST_FIRST").param("pageSize", "99"))
            .andExpect(status().isSeeOther)
            .andDo(document("admin-home-save-invalid"))
    }

    @Test
    fun `categories document listing, creation, update and the delete guard`() {
        val id = CategoryId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
        every { categories.list() } returns listOf(CategoryOverview(id, "Kotlin", 0, 2))
        every { categories.create("Spring", 1) } returns CategoryChangeResult.Saved(id)
        every { categories.update(id, "Kotlin", 3) } returns CategoryChangeResult.Saved(id)
        every { categories.delete(id) } returns CategoryChangeResult.AssignedToPosts

        mvc
            .perform(
                get("/admin/categories"),
            ).andExpect(status().isOk)
            .andExpect(content().string(containsString("Kotlin")))
            .andDo(document("admin-categories"))
        mvc
            .perform(post("/admin/categories").param("name", "Spring").param("position", "1"))
            .andExpect(status().isSeeOther)
            .andDo(document("admin-category-create"))
        mvc
            .perform(post("/admin/categories/{categoryId}", id.value.toString()).param("name", "Kotlin").param("position", "3"))
            .andExpect(status().isSeeOther)
            .andDo(document("admin-category-update", categoryIdParameter()))
        mvc
            .perform(post("/admin/categories/{categoryId}/delete", id.value.toString()))
            .andExpect(status().isSeeOther)
            .andDo(document("admin-category-delete-assigned", categoryIdParameter()))
    }

    @Test
    fun `classification documents the edit form and the single save of tags, series and source tags`() {
        val id = CategoryId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
        val secondId = CategoryId(UUID.fromString("00000000-0000-0000-0000-0000000000c2"))
        val seriesId = SeriesId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))
        val classification =
            PostClassification(
                postId,
                "Post",
                listOf(CategoryOverview(id, "Kotlin", 0, 1), CategoryOverview(secondId, "개발 기록", 1, 1)),
                setOf(id, secondId),
                listOf(SeriesOverview(seriesId, "DDD 적용기", "", 3, 2)),
                seriesId,
                listOf(SourceTagCandidate("kotlin", id, true), SourceTagCandidate("notion", null, false)),
            )
        every { classifications.get(postId) } returns PostClassificationLookupResult.Found(classification)
        every { classifications.save(postId, setOf(id, secondId), seriesId, setOf("notion")) } returns PostClassificationChangeResult.Saved
        every { classifications.save(postId, setOf(id), null, setOf("gone")) } returns PostClassificationChangeResult.SourceTagNotAvailable

        mvc
            .perform(get("/admin/posts/{postId}/categories", postId.value.toString()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("checked")))
            .andExpect(content().string(containsString("name=\"sourceTag\"")))
            .andDo(document("admin-post-categories", postIdParameter()))
        mvc
            .perform(
                post("/admin/posts/{postId}/categories", postId.value.toString())
                    .param("categoryId", id.value.toString(), secondId.value.toString())
                    .param("seriesId", seriesId.value.toString())
                    .param("sourceTag", "notion"),
            ).andExpect(status().isSeeOther)
            .andExpect(redirectedUrl("/admin/posts/${postId.value}/categories"))
            .andDo(document("admin-post-categories-save", postIdParameter()))
        mvc
            .perform(
                post("/admin/posts/{postId}/categories", postId.value.toString())
                    .param("categoryId", id.value.toString())
                    .param("sourceTag", "gone"),
            ).andExpect(status().isSeeOther)
            .andDo(document("admin-post-categories-save-rejected", postIdParameter()))
    }

    @Test
    fun `series documents listing, creation, editing, saving and deleting`() {
        val seriesId = SeriesId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))
        val members = listOf(SeriesPostSummary(postId, "첫 글", AdminPostStatus.PUBLISHED, null))
        val candidates =
            listOf(
                SeriesPostSummary(PostId(UUID.fromString("00000000-0000-0000-0000-000000000009")), "다른 글", AdminPostStatus.DRAFT, "다른 시리즈"),
            )
        every { seriesManagement.list() } returns listOf(SeriesOverview(seriesId, "DDD 적용기", "차근차근 적용합니다", 1, 1))
        every { seriesManagement.create("DDD 적용기", "차근차근 적용합니다") } returns SeriesChangeResult.Saved(seriesId)
        every { seriesManagement.get(seriesId) } returns
            SeriesLookupResult.Found(SeriesEditor(SeriesOverview(seriesId, "DDD 적용기", "차근차근 적용합니다", 1, 1), members, candidates))
        every { seriesManagement.save(seriesId, "DDD 적용기", "차근차근 적용합니다", listOf(postId)) } returns SeriesChangeResult.Saved(seriesId)
        every { seriesManagement.delete(seriesId) } returns SeriesChangeResult.Saved(seriesId)

        mvc
            .perform(
                get("/admin/series"),
            ).andExpect(status().isOk)
            .andExpect(content().string(containsString("DDD 적용기")))
            .andDo(document("admin-series"))
        mvc
            .perform(post("/admin/series").param("title", "DDD 적용기").param("description", "차근차근 적용합니다"))
            .andExpect(status().isSeeOther)
            .andExpect(redirectedUrl("/admin/series/${seriesId.value}"))
            .andDo(document("admin-series-create"))
        mvc
            .perform(get("/admin/series/{seriesId}", seriesId.value.toString()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("현재: 다른 시리즈")))
            .andDo(document("admin-series-edit", seriesIdParameter()))
        mvc
            .perform(
                post("/admin/series/{seriesId}", seriesId.value.toString())
                    .param("title", "DDD 적용기")
                    .param("description", "차근차근 적용합니다")
                    .param("postId", postId.value.toString()),
            ).andExpect(status().isSeeOther)
            .andExpect(redirectedUrl("/admin/series/${seriesId.value}"))
            .andDo(document("admin-series-save", seriesIdParameter()))
        mvc
            .perform(post("/admin/series/{seriesId}/delete", seriesId.value.toString()))
            .andExpect(status().isSeeOther)
            .andExpect(redirectedUrl("/admin/series"))
            .andDo(document("admin-series-delete", seriesIdParameter()))
    }

    @Test
    fun `series that are unknown or save rejected posts are documented as not found or an error notice`() {
        val seriesId = SeriesId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))
        every { seriesManagement.get(seriesId) } returns SeriesLookupResult.NotFound
        every { seriesManagement.save(seriesId, "제목", "", listOf(postId)) } returns SeriesChangeResult.PostNotFound

        mvc
            .perform(get("/admin/series/{seriesId}", seriesId.value.toString()))
            .andExpect(status().isNotFound)
            .andDo(document("admin-series-missing", seriesIdParameter()))
        mvc
            .perform(
                post("/admin/series/{seriesId}", seriesId.value.toString()).param("title", "제목").param("postId", postId.value.toString()),
            ).andExpect(status().isSeeOther)
            .andDo(document("admin-series-save-rejected", seriesIdParameter()))
    }

    private fun postIdParameter() = pathParameters(parameterWithName("postId").description("블로그가 저장한 게시글 UUID"))

    private fun seriesIdParameter() = pathParameters(parameterWithName("seriesId").description("시리즈 UUID"))

    private fun categoryIdParameter() = pathParameters(parameterWithName("categoryId").description("분류 UUID"))

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
        const val HTTPS_PORT = 443
    }
}
