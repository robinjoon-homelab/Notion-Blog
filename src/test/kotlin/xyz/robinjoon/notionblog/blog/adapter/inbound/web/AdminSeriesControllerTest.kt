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
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.SeriesChangeResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesEditor
import xyz.robinjoon.notionblog.blog.application.model.SeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostSummary
import xyz.robinjoon.notionblog.blog.application.port.input.SeriesManagementUseCase
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import java.util.UUID

// 시리즈 목록·편집 화면과 생성·저장·삭제 요청을 확인한다. 소속 규칙과 옮김은 서비스 테스트의 몫이다.
class AdminSeriesControllerTest {
    private val series = mockk<SeriesManagementUseCase>()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AdminSeriesController(series))
            .setViewResolvers(viewResolver())
            .build()
    private val id = WebFixtures.seriesId
    private val path = "/admin/series/${id.value}"
    private val first = PostId(UUID.nameUUIDFromBytes("first".toByteArray()))
    private val second = PostId(UUID.nameUUIDFromBytes("second".toByteArray()))
    private val third = PostId(UUID.nameUUIDFromBytes("third".toByteArray()))

    @Test
    fun `list shows each series with its counts and escapes text`() {
        every { series.list() } returns listOf(SeriesOverview(id, "<DDD> 적용기", "<b>설명</b>", 3, 2))

        val html =
            mockMvc
                .perform(
                    get("/admin/series"),
                ).andExpect(status().isOk)
                .andExpect(view().name("admin/series"))
                .andReturn()
                .response.contentAsString

        assertThat(
            html,
        ).contains("&lt;DDD&gt; 적용기", "&lt;b&gt;설명&lt;/b&gt;", "글 3개 · 공개 2개", "href=\"$path\"", "name=\"title\"", "aria-current=\"page\"")
        assertThat(html).doesNotContain("<DDD>", "<b>설명")
    }

    @Test
    fun `empty list invites the first series`() {
        every { series.list() } returns emptyList()

        assertThat(
            mockMvc
                .perform(get("/admin/series"))
                .andReturn()
                .response.contentAsString,
        ).contains("아직 시리즈가 없습니다", "첫 시리즈")
    }

    @Test
    fun `create trims the input and opens the new series for adding posts`() {
        every { series.create("DDD 적용기", "설명") } returns SeriesChangeResult.Saved(id)

        val result =
            mockMvc
                .perform(
                    post("/admin/series").param("title", "  DDD 적용기 ").param("description", " 설명 "),
                ).andExpect(redirectedUrl(path))
                .andReturn()

        assertThat(result.notice().error).isFalse()
        assertThat(result.notice().text).contains("글을 추가하고 저장하세요")
    }

    @Test
    fun `create rejects titles and descriptions outside the length rules`() {
        val tooLongTitle = "a".repeat(Series.MAX_TITLE_LENGTH + 1)
        val tooLongDescription = "d".repeat(Series.MAX_DESCRIPTION_LENGTH + 1)
        listOf("   " to "", tooLongTitle to "", "ok" to tooLongDescription).forEach { (title, description) ->
            val notice =
                mockMvc
                    .perform(post("/admin/series").param("title", title).param("description", description))
                    .andExpect(redirectedUrl("/admin/series"))
                    .andReturn()
                    .notice()
            assertThat(notice.error).isTrue()
            assertThat(notice.text).contains("제목은 1~100자", "설명은 300자 이하")
        }
    }

    @Test
    fun `edit lists members in order with their controls and candidates with the series they would leave`() {
        every { series.get(id) } returns SeriesLookupResult.Found(editor())

        val result =
            mockMvc
                .perform(get(path))
                .andExpect(status().isOk)
                .andExpect(view().name("admin/series-edit"))
                .andReturn()
        val html = result.response.contentAsString
        val members = html.substringAfter("data-series-members").substringBefore("data-series-candidates")
        val candidates = html.substringAfter("data-series-candidates")

        assertThat(listOf("첫 글", "둘째 &lt;글&gt;").map(members::indexOf)).isSorted().doesNotContain(-1)
        assertThat(
            members,
        ).contains("value=\"${first.value}\"", "&lt;글&gt;", "위로 이동", "아래로 이동", "시리즈에서 제외", "nb-badge-live", "nb-badge-draft")
        assertThat(members).doesNotContain(" disabled")
        assertThat(candidates).contains("value=\"${third.value}\"", "disabled", "현재: 다른 시리즈 → 저장하면 이 시리즈로 옮겨집니다", "시리즈에 추가")
        assertThat(html).contains("action=\"$path\"", "name=\"description\"", "시리즈 저장", "series-editor.js", "data-candidate-filter", "삭제")
        assertThat(html).doesNotContain("<글>")
    }

    @Test
    fun `edit without members or candidates explains both empty states`() {
        every { series.get(id) } returns SeriesLookupResult.Found(editor().copy(members = emptyList(), candidates = emptyList()))

        val html =
            mockMvc
                .perform(get(path))
                .andReturn()
                .response.contentAsString

        assertThat(html).contains("아직 글이 없습니다", "추가할 수 있는 글이 없습니다")
        assertThat(html).doesNotContain("data-candidate-filter")
    }

    @Test
    fun `edit of unknown or malformed series is not found`() {
        every { series.get(id) } returns SeriesLookupResult.NotFound

        mockMvc.perform(get(path)).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))
        mockMvc.perform(get("/admin/series/nope")).andExpect(status().isNotFound)
    }

    @Test
    fun `save sends the information and the posts in the submitted order without duplicates`() {
        every { series.save(id, "새 제목", "새 설명", listOf(second, first, third)) } returns SeriesChangeResult.Saved(id)

        val result =
            mockMvc
                .perform(
                    post(path)
                        .param("title", " 새 제목 ")
                        .param("description", "새 설명")
                        .param("postId", second.value.toString(), first.value.toString(), second.value.toString(), third.value.toString()),
                ).andExpect(status().isSeeOther)
                .andExpect(redirectedUrl(path))
                .andReturn()

        assertThat(result.notice().error).isFalse()
        assertThat(result.notice().text).contains("시리즈를 저장했습니다")
    }

    @Test
    fun `save with no posts empties the series and keeps its information`() {
        every { series.save(id, "제목", "", emptyList()) } returns SeriesChangeResult.Saved(id)

        val result = mockMvc.perform(post(path).param("title", "제목")).andExpect(redirectedUrl(path)).andReturn()

        assertThat(result.notice().error).isFalse()
    }

    @Test
    fun `save outcomes that did not save show an error or are not found`() {
        every { series.save(id, "제목", "", listOf(first)) } returns SeriesChangeResult.PostNotFound
        val missing =
            mockMvc
                .perform(
                    post(path).param("title", "제목").param("postId", first.value.toString()),
                ).andExpect(redirectedUrl(path))
                .andReturn()
                .notice()
        assertThat(missing.error).isTrue()
        assertThat(missing.text).contains("저장하지 않았습니다")

        every { series.save(id, "제목", "", emptyList()) } returns SeriesChangeResult.NotFound
        mockMvc.perform(post(path).param("title", "제목")).andExpect(status().isNotFound)
    }

    @Test
    fun `save rejects invalid information without asking the port and malformed ids`() {
        val invalid =
            mockMvc
                .perform(
                    post(path).param("title", "").param("description", "x"),
                ).andExpect(redirectedUrl(path))
                .andReturn()
                .notice()
        assertThat(invalid.error).isTrue()

        mockMvc.perform(post(path).param("title", "제목").param("postId", "bad")).andExpect(status().isBadRequest)
        mockMvc.perform(post("/admin/series/nope").param("title", "제목")).andExpect(status().isNotFound)
    }

    @Test
    fun `delete only removes the series and unknown series are not found`() {
        every { series.delete(id) } returns SeriesChangeResult.Saved(id)
        val deleted =
            mockMvc
                .perform(post("$path/delete"))
                .andExpect(redirectedUrl("/admin/series"))
                .andReturn()
                .notice()
        assertThat(deleted.error).isFalse()
        assertThat(deleted.text).contains("글과 태그, 공개 상태는 그대로입니다")

        every { series.delete(id) } returns SeriesChangeResult.NotFound
        mockMvc.perform(post("$path/delete")).andExpect(status().isNotFound)
        mockMvc.perform(post("/admin/series/nope/delete")).andExpect(status().isNotFound)
    }

    private fun editor(): SeriesEditor =
        SeriesEditor(
            SeriesOverview(id, "DDD 적용기", "설명", 2, 1),
            listOf(
                SeriesPostSummary(first, "첫 글", AdminPostStatus.PUBLISHED, null),
                SeriesPostSummary(second, "둘째 <글>", AdminPostStatus.DRAFT, null),
            ),
            listOf(SeriesPostSummary(third, "셋째 글", AdminPostStatus.CHANGES_PENDING, "다른 시리즈")),
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
