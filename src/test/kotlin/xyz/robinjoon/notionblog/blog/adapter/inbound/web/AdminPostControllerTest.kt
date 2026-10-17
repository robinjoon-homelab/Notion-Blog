package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPostsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPreviewView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostListQuery
import xyz.robinjoon.notionblog.blog.application.model.AdminPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.application.port.input.AdminPostQueryUseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminPostControllerTest {
    private val queries = mockk<AdminPostQueryUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T05:20:00Z"), ZoneOffset.UTC)
    private val assembler = AdminViewAssembler(clock, PostPageViewAssembler(clock))
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(AdminPostController(queries, assembler)).build()
    private val postId = WebFixtures.postId

    @Test
    fun `list shows every status with a state badge and Notion link`() {
        every { queries.list(query()) } returns
            AdminFixtures.list(
                listOf(summary("Draft one", AdminPostStatus.DRAFT), summary("Live one", AdminPostStatus.CHANGES_PENDING)),
            )

        val page = listPage("/admin/posts")

        assertThat(page.rows.map { it.title }).containsExactly("Draft one", "Live one")
        assertThat(page.rows[1].badges.map { it.label }).containsExactly("발행 중", "수정사항 있음")
        assertThat(page.rows[0].source.editUrl).isEqualTo("https://www.notion.so/page")
    }

    @Test
    fun `list passes the status filter and title search and marks the selected filter`() {
        every { queries.list(query(AdminPostStatus.PUBLISHED, "KOTLIN")) } returns
            AdminFixtures.list(listOf(summary("Kotlin tips", AdminPostStatus.PUBLISHED)))

        val page = listPage("/admin/posts?status=published&q=KOTLIN")

        assertThat(page.rows.map { it.title }).containsExactly("Kotlin tips")
        assertThat(page.search.total).isEqualTo(1)
        assertThat(page.filters.single { it.current }.label).isEqualTo("발행 중")
        assertThat(page.filters.map { it.label }).contains("보관됨")
        assertThat(page.filters.map { it.href }).contains("/admin/posts?q=KOTLIN")
    }

    @Test
    fun `list pages keep the filters in their links`() {
        val query = query(AdminPostStatus.DRAFT, page = 2)
        every { queries.list(query) } returns
            AdminFixtures.list((1..45).map { summary("Post $it", AdminPostStatus.DRAFT) }, query = query.page)

        val page = listPage("/admin/posts?status=draft&page=2")

        assertThat(page.rows).hasSize(20)
        assertThat(page.pagination.previousHref).isEqualTo("/admin/posts?status=draft")
        assertThat(page.pagination.nextHref).isEqualTo("/admin/posts?status=draft&page=3")
    }

    @Test
    fun `a page number below one is treated as the first page`() {
        every { queries.list(query()) } returns AdminFixtures.list(emptyList())

        assertThat(listPage("/admin/posts?page=0").rows).isEmpty()
    }

    @Test
    fun `preview shows the imported draft with the reviewed publish command`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()))

        val result =
            mockMvc
                .perform(
                    get("/admin/posts/${postId.value}"),
                ).andExpect(status().isOk)
                .andExpect(view().name("admin/preview"))
                .andReturn()
        val actions = result.modelOf<AdminPreviewView>("view").panel.actions

        assertThat(actions.publish?.revisionId).isEqualTo(WebFixtures.revisionId.value.toString())
        assertThat(actions.publish?.generation).isEqualTo(7)
        assertThat(actions.canUnpublish).isFalse()
        assertThat(actions.canArchive).isTrue()
    }

    @Test
    fun `published preview has no publish form but keeps unpublish and links to the public page`() {
        every { queries.get(postId, PreviewVersion.PUBLISHED) } returns
            AdminPostLookupResult.Found(
                AdminFixtures.preview(
                    AdminPostStatus.PUBLISHED,
                    AdminFixtures.actions(publish = null, canUnpublish = true),
                    AdminFixtures.publishedHistory,
                ),
            )

        val result = mockMvc.perform(get("/admin/posts/${postId.value}?version=published")).andReturn()
        val panel = result.modelOf<AdminPreviewView>("view").panel

        assertThat(panel.actions.publish).isNull()
        assertThat(panel.actions.canUnpublish).isTrue()
        assertThat(panel.actions.showingPublished).isTrue()
        assertThat(panel.actions.hasPublished).isTrue()
        assertThat(panel.publicHref).isEqualTo("/posts/${postId.value}")
    }

    @Test
    fun `post without an import offers the Notion link and refresh`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.NotImported(summary("Empty", AdminPostStatus.DRAFT), AdminFixtures.actions(publish = null))

        mockMvc.perform(get("/admin/posts/${postId.value}")).andExpect(status().isOk).andExpect(view().name("admin/pending"))
    }

    @Test
    fun `unknown or malformed posts are not found and missing site content is unavailable`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns AdminPostLookupResult.NotFound
        mockMvc.perform(get("/admin/posts/${postId.value}")).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))
        mockMvc.perform(get("/admin/posts/nope")).andExpect(status().isNotFound)

        every { queries.get(postId, PreviewVersion.LATEST) } returns AdminPostLookupResult.ContentUnavailable
        mockMvc.perform(get("/admin/posts/${postId.value}")).andExpect(status().isServiceUnavailable)
    }

    private fun listPage(url: String): AdminPostsView =
        mockMvc
            .perform(get(url))
            .andExpect(status().isOk)
            .andExpect(view().name("admin/posts"))
            .andReturn()
            .modelOf("page")

    private fun query(
        status: AdminPostStatus? = null,
        title: String = "",
        page: Int = 1,
    ) = AdminPostListQuery(status, title, PageQuery(page, 20))

    private fun summary(
        title: String,
        status: AdminPostStatus,
    ) = AdminFixtures.summary(title, status)
}
