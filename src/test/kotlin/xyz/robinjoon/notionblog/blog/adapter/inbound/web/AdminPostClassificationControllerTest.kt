package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPostCategoriesView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.application.model.PostClassification
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.application.model.SourceTagCandidate
import xyz.robinjoon.notionblog.blog.application.port.input.PostClassificationUseCase
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import java.util.UUID

// 분류 편집 화면(태그·시리즈·원문 태그)과 한 번에 저장하는 명령의 요청·응답을 확인한다. 서비스 규칙은 서비스 테스트의 몫이다.
class AdminPostClassificationControllerTest {
    private val classifications = mockk<PostClassificationUseCase>()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AdminPostCategoryController(classifications))
            .setViewResolvers(viewResolver())
            .build()
    private val postId = WebFixtures.postId
    private val path = "/admin/posts/${postId.value}/categories"
    private val kotlinTag = CategoryId(UUID.nameUUIDFromBytes("kotlin".toByteArray()))
    private val springTag = CategoryId(UUID.fromString("00000000-0000-0000-0000-0000000000c2"))
    private val series = SeriesId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))

    @Test
    fun `edit form shows tags series and source tag candidates with how each will be saved`() {
        every { classifications.get(postId) } returns PostClassificationLookupResult.Found(classification())

        val result =
            mockMvc
                .perform(get(path))
                .andExpect(status().isOk)
                .andExpect(view().name("admin/post-categories"))
                .andReturn()
        val html = result.response.contentAsString
        val model = result.modelOf<AdminPostCategoriesView>("view")

        assertThat(model.options.map { it.name to it.checked }).containsExactly("Kotlin" to true, "Spring" to false)
        assertThat(model.series.map { it.title to it.selected }).containsExactly("DDD 적용기" to false, "<시리즈>" to true)
        assertThat(model.sourceTags.map { it.name to it.tooLong })
            .containsExactly("kotlin" to false, "새 원문" to false, "이미 지정" to false, "a".repeat(Category.MAX_NAME_LENGTH + 1) to true)
        assertThat(html).contains("name=\"categoryId\"", "name=\"seriesId\"", "name=\"sourceTag\"", "분류 저장", "변경 취소", "시리즈 없음")
        assertThat(html).contains("같은 이름의 태그(Kotlin)를 재사용해 지정합니다", "새 태그로 만들어 지정합니다", "이미 이 글에 지정됨", "&lt;시리즈&gt;")
        assertThat(html).contains("태그 이름은 50자 이하만 옮길 수 있습니다").containsPattern("name=\"sourceTag\" value=\"a{51}\" disabled")
        assertThat(html).doesNotContain("<시리즈>", "카테고리")
    }

    @Test
    fun `edit form without tags series or source tags explains each empty state`() {
        every { classifications.get(postId) } returns
            PostClassificationLookupResult.Found(
                classification().copy(categories = emptyList(), series = emptyList(), sourceTags = emptyList()),
            )

        val html =
            mockMvc
                .perform(get(path))
                .andReturn()
                .response.contentAsString

        assertThat(html).contains("지정할 태그가 없습니다", "지정할 시리즈가 없습니다", "옮길 원문 태그가 없습니다", "분류 저장")
    }

    @Test
    fun `no series selected checks the none option`() {
        every { classifications.get(postId) } returns
            PostClassificationLookupResult.Found(classification().copy(selectedSeriesId = null))

        val html =
            mockMvc
                .perform(get(path))
                .andReturn()
                .response.contentAsString

        assertThat(html).containsPattern("value=\"\"[^>]*checked")
    }

    @Test
    fun `edit of unknown or malformed posts is not found`() {
        every { classifications.get(postId) } returns PostClassificationLookupResult.NotFound

        mockMvc.perform(get(path)).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))
        mockMvc.perform(get("/admin/posts/nope/categories")).andExpect(status().isNotFound)
    }

    @Test
    fun `save replaces tags with the series and the chosen source tags in one request`() {
        every { classifications.save(postId, setOf(kotlinTag, springTag), series, setOf("새 원문", "kotlin")) } returns
            PostClassificationChangeResult.Saved

        val saved =
            mockMvc
                .perform(
                    post(path)
                        .param("categoryId", kotlinTag.value.toString(), springTag.value.toString())
                        .param("seriesId", series.value.toString())
                        .param("sourceTag", "새 원문", "kotlin", "kotlin"),
                ).andExpect(status().isSeeOther)
                .andExpect(redirectedUrl(path))
                .andReturn()

        assertThat(saved.notice().error).isFalse()
        assertThat(saved.notice().text).contains("분류를 저장했습니다", "본문과 공개 상태는 바뀌지 않습니다")
    }

    @Test
    fun `an empty submission clears the tags and the series and moves nothing`() {
        every { classifications.save(postId, emptySet(), null, emptySet()) } returns PostClassificationChangeResult.Saved

        val cleared = mockMvc.perform(post(path).param("seriesId", "")).andExpect(redirectedUrl(path)).andReturn()
        val omitted = mockMvc.perform(post(path)).andExpect(redirectedUrl(path)).andReturn()

        assertThat(cleared.notice().error).isFalse()
        assertThat(omitted.notice().error).isFalse()
    }

    @Test
    fun `rejected saves return to the form with an error and explain that nothing was saved`() {
        every { classifications.save(postId, setOf(kotlinTag), null, emptySet()) } returns PostClassificationChangeResult.CategoryNotFound
        every { classifications.save(postId, emptySet(), series, emptySet()) } returns PostClassificationChangeResult.SeriesNotFound
        every { classifications.save(postId, emptySet(), null, setOf("x")) } returns PostClassificationChangeResult.SourceTagNotAvailable

        val category =
            mockMvc
                .perform(
                    post(path).param("categoryId", kotlinTag.value.toString()),
                ).andExpect(redirectedUrl(path))
                .andReturn()
                .notice()
        val seriesNotice =
            mockMvc
                .perform(
                    post(path).param("seriesId", series.value.toString()),
                ).andExpect(redirectedUrl(path))
                .andReturn()
                .notice()
        val source =
            mockMvc
                .perform(post(path).param("sourceTag", "x"))
                .andExpect(redirectedUrl(path))
                .andReturn()
                .notice()

        assertThat(listOf(category, seriesNotice, source).map { it.error }).containsOnly(true)
        assertThat(category.text).contains("태그")
        assertThat(seriesNotice.text).contains("시리즈")
        assertThat(source.text).contains("원문 태그", "1~50자")
    }

    @Test
    fun `malformed ids are a bad request and unknown posts are not found`() {
        mockMvc.perform(post(path).param("categoryId", "bad")).andExpect(status().isBadRequest)
        mockMvc.perform(post(path).param("seriesId", "bad")).andExpect(status().isBadRequest)
        every { classifications.save(postId, emptySet(), null, emptySet()) } returns PostClassificationChangeResult.PostNotFound
        mockMvc.perform(post(path)).andExpect(status().isNotFound)
        mockMvc.perform(post("/admin/posts/nope/categories")).andExpect(status().isNotFound)
    }

    private fun classification() =
        PostClassification(
            postId,
            "Post",
            listOf(CategoryOverview(kotlinTag, "Kotlin", 0, 2), CategoryOverview(springTag, "Spring", 1, 1)),
            setOf(kotlinTag),
            listOf(
                SeriesOverview(UUID.nameUUIDFromBytes("s".toByteArray()).let(::SeriesId), "DDD 적용기", "", 3, 2),
                SeriesOverview(series, "<시리즈>", "", 1, 1),
            ),
            series,
            listOf(
                SourceTagCandidate("kotlin", kotlinTag, false),
                SourceTagCandidate("새 원문", null, false),
                SourceTagCandidate("이미 지정", springTag, true),
                SourceTagCandidate("a".repeat(Category.MAX_NAME_LENGTH + 1), null, false),
            ),
        )

    private fun MvcResult.notice(): NoticeView = checkNotNull(flashMap["notice"]) as NoticeView

    private fun viewResolver(): ThymeleafViewResolver {
        val templateResolver =
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                templateMode = TemplateMode.HTML
                characterEncoding = "UTF-8"
            }
        val engine = SpringTemplateEngine().apply { setTemplateResolver(templateResolver) }
        return ThymeleafViewResolver().apply {
            templateEngine = engine
            characterEncoding = "UTF-8"
            contentType = "text/html;charset=UTF-8"
        }
    }
}
