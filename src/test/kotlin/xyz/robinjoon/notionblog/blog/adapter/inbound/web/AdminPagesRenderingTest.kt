package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.application.model.AdminPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.CurrentClassification
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.application.model.SeriesMembership
import xyz.robinjoon.notionblog.blog.application.port.input.AdminPostQueryUseCase
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// 실제 컨트롤러와 Thymeleaf 템플릿을 거친 HTML의 상태별 화면을 확인한다.
class AdminPagesRenderingTest {
    private val queries = mockk<AdminPostQueryUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T05:20:00Z"), ZoneOffset.UTC)
    private val postId = WebFixtures.postId
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AdminPostController(queries, AdminViewAssembler(clock, PostPageViewAssembler(clock))))
            .setViewResolvers(viewResolver())
            .build()

    @Test
    fun `empty list tells the user to write in the connected Notion database`() {
        every { queries.list(any()) } returns AdminFixtures.list(emptyList())

        val html = html("/admin/posts")

        assertThat(html).contains("아직 글이 없습니다", "연결된 Notion 데이터베이스", "새 글은 연결된 Notion 글 데이터베이스에서 작성하세요")
        assertThat(html).doesNotContain("<table", "+ 새 글", "name=\"requestId\"")
    }

    @Test
    fun `every admin page lists the four menus the public blog link and the current menu`() {
        every { queries.list(any()) } returns AdminFixtures.list(emptyList())

        val html = html("/admin/posts")

        assertThat(listOf("글 관리", "태그", "시리즈", "설정", "내 블로그").map(html::indexOf)).isSorted().doesNotContain(-1)
        assertThat(html).contains("href=\"/admin/series\"", "href=\"/admin/categories\"", "href=\"/admin/home\"", "aria-current=\"page\"")
        assertThat(html).doesNotContain("게시글", "카테고리", "홈 화면", "공개 홈")
    }

    @Test
    fun `filtered list without matches explains the empty result`() {
        every { queries.list(any()) } returns AdminFixtures.list(emptyList())

        assertThat(html("/admin/posts?q=missing")).contains("조건에 맞는 글이 없습니다")
    }

    @Test
    fun `list shows the filters with the all filter explained as excluding archived posts`() {
        every { queries.list(any()) } returns AdminFixtures.list(listOf(summary("Hello", AdminPostStatus.PUBLISHED)))

        val html = html("/admin/posts")

        assertThat(html).contains("전체 (보관 제외)", "공개", "비공개", "보관", "전체에는 보관한 글이 들어 있지 않습니다")
    }

    @Test
    fun `list rows escape titles and open Notion in a new tab`() {
        every { queries.list(any()) } returns AdminFixtures.list(listOf(summary("<script>x</script>", AdminPostStatus.UNPUBLISHED, 2)))

        val html = html("/admin/posts")

        assertThat(
            html,
        ).contains("&lt;script&gt;x&lt;/script&gt;", "비공개", "파일 2개", "Notion에서 열기", "target=\"_blank\"", "rel=\"noopener noreferrer\"")
        assertThat(html).doesNotContain("<script>x")
    }

    @Test
    fun `preview of a draft links to the publish review instead of publishing directly`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()))

        val html = html("/admin/posts/${postId.value}")

        assertThat(html).contains("/ui/notion-shell.js", "data-toggle-menu")
        assertThat(html).contains("/admin/posts/${postId.value}/review", "발행하기 확인", "/refresh\"", "/archive\"", "분류 편집", "Notion에서 수정")
        assertThat(html).doesNotContain("name=\"revisionId\"", "/publish\"", "/unpublish\"", "/restore\"", "게시")
    }

    @Test
    fun `preview of a live post with pending changes offers change publish review and switching to unpublish`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(
                    AdminPostStatus.CHANGES_PENDING,
                    AdminFixtures.actions(canUnpublish = true),
                    AdminFixtures.publishedHistory,
                ),
            )

        val html = html("/admin/posts/${postId.value}")

        assertThat(html).contains("변경 발행 확인", "비공개로 전환", "/unpublish\"", "가져온 변경 있음", "가져온 내용", "공개 중인 내용", "Sep 1, 2026")
        assertThat(html).doesNotContain("발행 취소", "수정사항 있음", "가져온 초안")
    }

    @Test
    fun `preview shows app tags series position and Notion source tags separately and read only`() {
        val classification =
            CurrentClassification(
                listOf(ListedCategory(CategoryId(UUID.nameUUIDFromBytes("t".toByteArray())), "<Kotlin>")),
                SeriesMembership(WebFixtures.seriesId, "DDD 적용기", 2, 5),
            )
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()),
                classification,
            )

        val html = html("/admin/posts/${postId.value}")

        assertThat(
            html,
        ).contains("&lt;Kotlin&gt;", "DDD 적용기 (2 / 5)", "href=\"/admin/series/${WebFixtures.seriesId.value}\"", "Notion 원문 태그")
        assertThat(html.substringAfter("Notion 원문 태그")).contains("kotlin")
        assertThat(html).doesNotContain("nb-series-toc")
    }

    @Test
    fun `preview without classification says nothing is assigned`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()))

        assertThat(html("/admin/posts/${postId.value}")).contains("지정된 태그 없음", "속한 시리즈 없음")
    }

    @Test
    fun `a failed import shows a separate warning and keeps the public state badge`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(AdminPostStatus.PUBLISHED, AdminFixtures.actions(publish = null)).let {
                    it.copy(summary = it.summary.copy(source = it.summary.source.copy(failureKind = SyncFailureKind.AUTHENTICATION)))
                },
            )

        val html = html("/admin/posts/${postId.value}")

        assertThat(html).contains("nb-badge-live", "nb-badge-warn", "가져오기 문제", "Notion 인증에 실패했습니다")
    }

    @Test
    fun `archived preview offers the public version only when it was published before`() {
        val archived = AdminFixtures.actions(publish = null, canArchive = false, canRestore = true)
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.ARCHIVED, archived, AdminFixtures.publishedHistory))
        assertThat(html("/admin/posts/${postId.value}")).contains("공개 중인 내용", "Sep 1, 2026")

        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.ARCHIVED, archived))
        assertThat(html("/admin/posts/${postId.value}")).doesNotContain("공개 중인 내용", "발행일")
    }

    @Test
    fun `archived preview can only be restored`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(
                    AdminPostStatus.ARCHIVED,
                    AdminFixtures.actions(publish = null, canArchive = false, canRestore = true),
                ),
            )

        val html = html("/admin/posts/${postId.value}")

        assertThat(html).contains("보관", "/restore\"", "보관 해제")
        assertThat(html).doesNotContain("/publish\"", "/archive\"", "/review")
    }

    @Test
    fun `review shows the imported content read only with its summary and the publish form for exactly that revision`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()))

        val html = html("/admin/posts/${postId.value}/review")

        assertThat(html).contains("발행 확인", "Hello", "A summary", "/ui/default-post-thumbnail.svg", "분류 편집", "Notion에서 수정", "최신 내용 가져오기")
        assertThat(html).contains("/ui/notion-shell.js")
        assertThat(html.substringAfter("nb-preview")).contains("Imported body")
        assertThat(html.substringAfter("nb-preview").substringBefore("aria-label=\"발행\"")).doesNotContain("<form", "contenteditable")
        assertThat(
            html,
        ).contains("action=\"/admin/posts/${postId.value}/publish\"", "name=\"revisionId\"", WebFixtures.revisionId.value.toString())
        assertThat(html).contains("name=\"generation\"", "value=\"7\"", "발행하기", "이번에 공개할 가져온 내용: 2026-10-16 05:00", "발행하지 않고 돌아가기")
        assertThat(html).doesNotContain("/unpublish\"", "/archive\"")
    }

    @Test
    fun `review of a live post is a change publish and can switch to the public content for comparison`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(AdminPostStatus.CHANGES_PENDING, AdminFixtures.actions(), AdminFixtures.publishedHistory),
            )

        val html = html("/admin/posts/${postId.value}/review")

        assertThat(html).contains("변경 발행", "version=published", "공개 중인 내용")
    }

    @Test
    fun `comparing with the public content renders the comparison without a publish form and does not redirect`() {
        every { queries.get(postId, PreviewVersion.PUBLISHED) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(
                    AdminPostStatus.PUBLISHED,
                    AdminFixtures.actions(publish = null, canUnpublish = true),
                    AdminFixtures.publishedHistory,
                ),
            )

        val response = mockMvc.perform(get("/admin/posts/${postId.value}/review?version=published")).andReturn().response

        assertThat(response.status).isEqualTo(200)
        assertThat(
            response.contentAsString,
        ).contains("공개 중인 내용을 비교하는 화면입니다", "발행 확인").doesNotContain("name=\"revisionId\"", "action=\"/admin/posts/${postId.value}/publish\"")
    }

    @Test
    fun `review with nothing to publish still renders and explains why`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(
                    AdminPostStatus.PUBLISHED,
                    AdminFixtures.actions(publish = null, canUnpublish = true),
                    AdminFixtures.publishedHistory,
                ),
            )
        val unchanged = html("/admin/posts/${postId.value}/review")

        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(
                    AdminPostStatus.ARCHIVED,
                    AdminFixtures.actions(publish = null, canArchive = false, canRestore = true),
                ),
            )
        val archived = html("/admin/posts/${postId.value}/review")

        assertThat(unchanged).contains("발행할 새 가져온 내용이 없습니다").doesNotContain("name=\"revisionId\"")
        assertThat(archived).contains("보관된 글은 발행할 수 없습니다", "먼저 보관을 해제하세요").doesNotContain("name=\"revisionId\"", "발행할 새 가져온 내용이 없습니다")
    }

    @Test
    fun `pending import page asks to fetch from Notion`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.NotImported(summary("Empty", AdminPostStatus.DRAFT), AdminFixtures.actions(publish = null))

        assertThat(html("/admin/posts/${postId.value}")).contains("아직 Notion에서 가져온 내용이 없습니다", "최신 내용 가져오기", "분류 편집")
    }

    private fun html(url: String): String =
        mockMvc
            .perform(get(url))
            .andReturn()
            .response.contentAsString

    private fun summary(
        title: String,
        status: AdminPostStatus,
        missingMedia: Int = 0,
    ) = AdminFixtures.summary(title, status, missingMedia)

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
