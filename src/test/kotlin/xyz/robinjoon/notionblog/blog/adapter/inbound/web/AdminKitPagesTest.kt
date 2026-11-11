package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostClassification
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.application.model.SeriesEditor
import xyz.robinjoon.notionblog.blog.application.model.SeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostSummary
import xyz.robinjoon.notionblog.blog.application.model.SourceTagCandidate
import xyz.robinjoon.notionblog.blog.application.port.input.AdminPostQueryUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.CategoryManagementUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.HomeConfigurationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.PostClassificationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SeriesManagementUseCase
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.support.web.pageMockMvc
import xyz.robinjoon.notionblog.support.web.renderedPage
import xyz.robinjoon.notionblog.support.web.saveRenderedPage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// 관리 화면이 UI 키트의 셸과 컴포넌트로 그려지는지 확인하고, 렌더링 결과를 html-validate 검사 대상으로 저장한다.
class AdminKitPagesTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T05:20:00Z"), ZoneOffset.UTC)
    private val queries = mockk<AdminPostQueryUseCase>()
    private val classifications = mockk<PostClassificationUseCase>()
    private val categories = mockk<CategoryManagementUseCase>()
    private val series = mockk<SeriesManagementUseCase>()
    private val homeConfigurations = mockk<HomeConfigurationUseCase>()
    private val posts = pageMockMvc(AdminPostController(queries, AdminViewAssembler(clock, PostPageViewAssembler(clock))))
    private val postId = WebFixtures.postId
    private val seriesId = WebFixtures.seriesId
    private val tagId = CategoryId(UUID.nameUUIDFromBytes("kotlin".toByteArray()))

    @Test
    fun `post list uses the kit shell with the posts menu current and a table inside its wrapper`() {
        every { queries.list(any()) } returns
            AdminFixtures.list(
                listOf(
                    AdminFixtures.summary("공개 글", AdminPostStatus.PUBLISHED),
                    AdminFixtures.summary("변경된 글", AdminPostStatus.CHANGES_PENDING, missingMedia = 2),
                    AdminFixtures.summary("보관한 글", AdminPostStatus.ARCHIVED, failureKind = null),
                ),
            )

        val html = posts.renderedPage("/admin/posts", "admin-posts")

        assertThat(html).contains("class=\"ui-shell\"", "class=\"ui-sidebar\"", "class=\"ui-page-head\"", "class=\"ui-table-wrap\"")
        assertThat(html).contains("class=\"ui-badge ui-badge-success\"", "class=\"ui-badge ui-badge-warning\"", "class=\"ui-badge\"")
        assertThat(html).contains("href=\"/admin/posts\" aria-current=\"page\"", "파일 2개")
        assertThat(html).doesNotContain("ui-notice")
    }

    @Test
    fun `a flash notice after a redirect is shown through the kit notice and an error is marked`() {
        every { queries.list(any()) } returns AdminFixtures.list(listOf(AdminFixtures.summary("공개 글", AdminPostStatus.PUBLISHED)))

        val saved = posts.renderedWithNotice("/admin/posts", "admin-posts-notice", NoticeView("저장했습니다.", false))
        val failed = posts.renderedWithNotice("/admin/posts", "admin-posts-notice-error", NoticeView("저장하지 못했습니다.", true))

        assertThat(saved).contains("class=\"ui-notice\"", "저장했습니다.").doesNotContain("ui-notice-error")
        assertThat(failed).contains("class=\"ui-notice ui-notice-error\"", "저장하지 못했습니다.")
    }

    @Test
    fun `empty post list uses the kit empty state and no table`() {
        every { queries.list(any()) } returns AdminFixtures.list(emptyList())

        val html = posts.renderedPage("/admin/posts", "admin-posts-empty")

        assertThat(html).contains("class=\"ui-empty\"", "아직 글이 없습니다").doesNotContain("<table")
    }

    @Test
    fun `pending import page keeps its refresh and classification actions in a kit panel`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.NotImported(AdminFixtures.summary("Empty", AdminPostStatus.DRAFT), AdminFixtures.actions(publish = null))

        val html = posts.renderedPage("/admin/posts/${postId.value}", "admin-pending")

        assertThat(html).contains("class=\"ui-shell\"", "class=\"ui-panel\"", "ui-button ui-button-primary", "/refresh\"", "분류 편집")
    }

    @Test
    fun `preview loads the presentation assets with integrity and renders the post inside the kit shell`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(AdminPostStatus.CHANGES_PENDING, AdminFixtures.actions(), AdminFixtures.publishedHistory),
            )

        val html = posts.renderedPage("/admin/posts/${postId.value}", "admin-preview")

        assertThat(html).contains("class=\"ui-shell\"", "class=\"notion-page nb-preview", "Imported body", "ui-button ui-button-primary")
        assertThat(html).containsPattern("<link rel=\"stylesheet\" href=\"/presentation/notion.css\"[^>]*integrity=\"sha256-style\"")
        assertThat(html).doesNotContain("<style", "style=\"", "onerror=")
    }

    @Test
    fun `review keeps the publish form for exactly the reviewed revision inside the kit shell`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()))

        val html = posts.renderedPage("/admin/posts/${postId.value}/review", "admin-review")

        assertThat(
            html,
        ).contains("class=\"ui-shell\"", "class=\"ui-panel nb-review-summary\"", "name=\"revisionId\"", "/app/thumbnail-fallback.js")
        assertThat(html).containsPattern("<link rel=\"stylesheet\" href=\"/presentation/notion.css\"[^>]*integrity=\"sha256-style\"")
    }

    @Test
    fun `post classification form groups the choices in kit panels`() {
        val classificationMvc = pageMockMvc(AdminPostCategoryController(classifications))
        every { classifications.get(postId) } returns PostClassificationLookupResult.Found(classification())

        val html = classificationMvc.renderedPage("/admin/posts/${postId.value}/categories", "admin-post-categories")

        assertThat(
            html,
        ).contains("class=\"ui-shell\"", "class=\"nb-form\"", "name=\"categoryId\"", "name=\"seriesId\"", "name=\"sourceTag\"")
        assertThat(html).contains("href=\"/admin/posts\" aria-current=\"page\"")
    }

    @Test
    fun `post classification form without choices shows kit empty states`() {
        val classificationMvc = pageMockMvc(AdminPostCategoryController(classifications))
        every { classifications.get(postId) } returns
            PostClassificationLookupResult.Found(
                classification().copy(categories = emptyList(), series = emptyList(), sourceTags = emptyList()),
            )

        val html = classificationMvc.renderedPage("/admin/posts/${postId.value}/categories", "admin-post-categories-empty")

        assertThat(html.split("class=\"ui-empty\"")).hasSize(4)
        assertThat(html).contains("지정할 태그가 없습니다", "지정할 시리즈가 없습니다", "옮길 원문 태그가 없습니다")
    }

    @Test
    fun `tag list edits each tag in its own panel and the empty list uses the kit empty state`() {
        val tagsMvc = pageMockMvc(AdminCategoryController(categories))
        every { categories.list() } returns
            listOf(CategoryOverview(tagId, "Kotlin", 0, 2), CategoryOverview(CategoryId(UUID.randomUUID()), "빈 태그", 1, 0))

        val html = tagsMvc.renderedPage("/admin/categories", "admin-categories")

        assertThat(html).contains("href=\"/admin/categories\" aria-current=\"page\"", "class=\"ui-panel\"", "삭제", "글이 지정되어 삭제할 수 없음")

        every { categories.list() } returns emptyList()
        assertThat(tagsMvc.renderedPage("/admin/categories", "admin-categories-empty")).contains("class=\"ui-empty\"", "아직 태그가 없습니다")
    }

    @Test
    fun `series list is a kit table and the empty list uses the kit empty state`() {
        val seriesMvc = pageMockMvc(AdminSeriesController(series))
        every { series.list() } returns listOf(SeriesOverview(seriesId, "DDD 적용기", "설명", 3, 2))

        val html = seriesMvc.renderedPage("/admin/series", "admin-series")

        assertThat(html).contains("href=\"/admin/series\" aria-current=\"page\"", "class=\"ui-table-wrap\"", "글 3개 · 공개 2개")

        every { series.list() } returns emptyList()
        assertThat(seriesMvc.renderedPage("/admin/series", "admin-series-empty")).contains("class=\"ui-empty\"", "아직 시리즈가 없습니다")
    }

    @Test
    fun `series editor keeps the script hooks and uses kit buttons`() {
        val seriesMvc = pageMockMvc(AdminSeriesController(series))
        val first = PostId(UUID.nameUUIDFromBytes("first".toByteArray()))
        val second = PostId(UUID.nameUUIDFromBytes("second".toByteArray()))
        every { series.get(seriesId) } returns
            SeriesLookupResult.Found(
                SeriesEditor(
                    SeriesOverview(seriesId, "DDD 적용기", "설명", 1, 1),
                    listOf(SeriesPostSummary(first, "첫 글", AdminPostStatus.PUBLISHED, null)),
                    listOf(SeriesPostSummary(second, "둘째 글", AdminPostStatus.DRAFT, "다른 시리즈")),
                ),
            )

        val html = seriesMvc.renderedPage("/admin/series/${seriesId.value}", "admin-series-edit")

        assertThat(
            html,
        ).contains("data-series-editor", "data-series-members", "data-series-candidates", "data-candidate-filter", "/app/series-editor.js")
        assertThat(html).contains("class=\"ui-button\" data-action=\"up\"", "nb-series-title", "ui-button ui-button-danger")
    }

    @Test
    fun `home settings keep the editor hooks inside kit panels`() {
        val homeMvc = pageMockMvc(AdminHomeController(homeConfigurations))
        every { homeConfigurations.get() } returns HomeConfiguration.defaults()

        val html = homeMvc.renderedPage("/admin/home", "admin-home")

        assertThat(
            html,
        ).contains("href=\"/admin/home\" aria-current=\"page\"", "data-home-editor", "data-preview-list", "/app/home-editor.js")
        assertThat(html).contains("name=\"layout\"", "name=\"order\"", "name=\"pageSize\"", "name=\"showSummary\"", "class=\"ui-panel\"")
    }

    private fun MockMvc.renderedWithNotice(
        path: String,
        snapshot: String,
        notice: NoticeView,
    ): String {
        val html =
            perform(get(path).accept(MediaType.TEXT_HTML).flashAttr("notice", notice))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        saveRenderedPage(snapshot, html)
        return html
    }

    private fun classification() =
        PostClassification(
            postId,
            "Post",
            listOf(CategoryOverview(tagId, "Kotlin", 0, 2)),
            setOf(tagId),
            listOf(SeriesOverview(SeriesId(UUID.nameUUIDFromBytes("s".toByteArray())), "DDD 적용기", "", 3, 2)),
            null,
            listOf(SourceTagCandidate("kotlin", tagId, false)),
        )
}
