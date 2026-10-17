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
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.application.port.input.AdminPostQueryUseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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

        assertThat(html).contains("아직 글이 없습니다", "연결된 Notion 데이터베이스")
        assertThat(html).doesNotContain("<table", "+ 새 글", "name=\"requestId\"")
    }

    @Test
    fun `filtered list without matches explains the empty result`() {
        every { queries.list(any()) } returns AdminFixtures.list(emptyList())

        assertThat(html("/admin/posts?q=missing")).contains("조건에 맞는 글이 없습니다")
    }

    @Test
    fun `list rows escape titles and open Notion in a new tab`() {
        every { queries.list(any()) } returns AdminFixtures.list(listOf(summary("<script>x</script>", AdminPostStatus.UNPUBLISHED, 2)))

        val html = html("/admin/posts")

        assertThat(
            html,
        ).contains("&lt;script&gt;x&lt;/script&gt;", "발행 취소", "파일 2개", "Notion에서 열기", "target=\"_blank\"", "rel=\"noopener noreferrer\"")
        assertThat(html).doesNotContain("<script>x")
    }

    @Test
    fun `preview of a draft offers publish with the reviewed revision and generation but no unpublish`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()))

        val html = html("/admin/posts/${postId.value}")

        assertThat(html).contains("name=\"revisionId\"", WebFixtures.revisionId.value.toString(), "name=\"generation\"", "value=\"7\"")
        assertThat(html).contains("/publish\"", "/refresh\"", "/archive\"", "Notion에서 작성")
        assertThat(html).doesNotContain("/unpublish\"", "/restore\"")
    }

    @Test
    fun `preview of a live post with pending changes offers update and unpublish`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(
                    AdminPostStatus.CHANGES_PENDING,
                    AdminFixtures.actions(canUnpublish = true),
                    AdminFixtures.publishedHistory,
                ),
            )

        val html = html("/admin/posts/${postId.value}")

        assertThat(html).contains("변경사항 게시", "/unpublish\"", "수정사항 있음", "공개 중인 글", "Sep 1, 2026")
    }

    @Test
    fun `archived preview offers the public version only when it was published before`() {
        val archived = AdminFixtures.actions(publish = null, canArchive = false, canRestore = true)
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.ARCHIVED, archived, AdminFixtures.publishedHistory))
        assertThat(html("/admin/posts/${postId.value}")).contains("공개 중인 글", "Sep 1, 2026")

        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.ARCHIVED, archived))
        assertThat(html("/admin/posts/${postId.value}")).doesNotContain("공개 중인 글", "게시일")
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

        assertThat(html).contains("보관됨", "/restore\"", "보관 해제")
        assertThat(html).doesNotContain("/publish\"", "/archive\"")
    }

    @Test
    fun `pending import page asks to fetch from Notion`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.NotImported(summary("Empty", AdminPostStatus.DRAFT), AdminFixtures.actions(publish = null))

        assertThat(html("/admin/posts/${postId.value}")).contains("아직 Notion에서 가져온 내용이 없습니다", "최신 내용 가져오기")
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
