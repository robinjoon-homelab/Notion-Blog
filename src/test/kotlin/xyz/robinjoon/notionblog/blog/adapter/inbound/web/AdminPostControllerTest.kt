package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
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
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
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
    fun `list shows public and private posts with separate state and notice badges and a Notion link`() {
        every { queries.list(query()) } returns
            AdminFixtures.list(
                listOf(
                    summary("Draft one", AdminPostStatus.DRAFT),
                    summary("Live one", AdminPostStatus.CHANGES_PENDING),
                    summary("Live two", AdminPostStatus.PUBLISHED),
                    summary("Stopped", AdminPostStatus.UNPUBLISHED),
                ),
            )

        val page = listPage("/admin/posts")

        assertThat(page.rows.map { it.title }).containsExactly("Draft one", "Live one", "Live two", "Stopped")
        assertThat(page.rows.map { row -> row.badges.map { it.label } })
            .containsExactly(listOf("비공개"), listOf("공개", "가져온 변경 있음"), listOf("공개"), listOf("비공개"))
        assertThat(page.rows[1].badges.map { it.tone }).containsExactly("live", "change")
        assertThat(page.rows[0].source.editUrl).isEqualTo("https://www.notion.so/page")
    }

    @Test
    fun `an archived post is labelled as archived`() {
        every { queries.list(query(AdminPostVisibility.ARCHIVED)) } returns
            AdminFixtures.list(listOf(summary("Old", AdminPostStatus.ARCHIVED)))

        assertThat(
            listPage("/admin/posts?status=archived")
                .rows
                .single()
                .badges
                .map { it.label },
        ).containsExactly("보관")
    }

    @Test
    fun `a failed import is a separate warning badge and does not change the public state badge`() {
        every { queries.list(query()) } returns
            AdminFixtures.list(listOf(AdminFixtures.summary("Live", AdminPostStatus.PUBLISHED, failureKind = SyncFailureKind.ACCESS)))

        val row = listPage("/admin/posts").rows.single()

        assertThat(row.badges.map { it.label to it.tone }).containsExactly("공개" to "live", "가져오기 문제" to "warn")
        assertThat(row.source.problem).contains("공개된 글은 그대로")
    }

    @ParameterizedTest
    @CsvSource(
        "public, PUBLIC",
        "published, PUBLIC",
        "changes_pending, PUBLIC",
        "private, PRIVATE",
        "draft, PRIVATE",
        "unpublished, PRIVATE",
        "archived, ARCHIVED",
        "PUBLIC, PUBLIC",
    )
    fun `current and legacy status values select the matching visibility group`(
        value: String,
        groupName: String,
    ) {
        val group = AdminPostVisibility.valueOf(groupName)
        every { queries.list(query(group)) } returns AdminFixtures.list(emptyList())

        val page = listPage("/admin/posts?status=$value")

        assertThat(page.filters.single { it.current }.label).isEqualTo(group.label)
        assertThat(page.search.status).isEqualTo(group.param)
    }

    @Test
    fun `an unknown status falls back to every post except archived ones`() {
        every { queries.list(query()) } returns AdminFixtures.list(emptyList())

        val page = listPage("/admin/posts?status=bogus")

        assertThat(page.filters.single { it.current }.label).isEqualTo("전체 (보관 제외)")
        assertThat(page.search.status).isEmpty()
    }

    @Test
    fun `list passes the search and keeps it in the filter links while explaining that all excludes archived posts`() {
        every { queries.list(query(AdminPostVisibility.PUBLIC, "c++")) } returns
            AdminFixtures.list(listOf(summary("c++ tips", AdminPostStatus.PUBLISHED)))

        val page = listPage(get("/admin/posts").param("status", "public").param("q", "c++"))

        assertThat(page.rows.map { it.title }).containsExactly("c++ tips")
        assertThat(page.search.total).isEqualTo(1)
        assertThat(page.filters.map { it.label }).containsExactly("전체 (보관 제외)", "공개", "비공개", "보관")
        assertThat(page.filters.map { it.href }).containsExactly(
            "/admin/posts?q=c%2B%2B",
            "/admin/posts?status=public&q=c%2B%2B",
            "/admin/posts?status=private&q=c%2B%2B",
            "/admin/posts?status=archived&q=c%2B%2B",
        )
        assertThat(page.allNote).contains("보관한 글이 들어 있지 않습니다")
    }

    @Test
    fun `list pages keep the filters in their links and legacy values become canonical links`() {
        val query = query(AdminPostVisibility.PRIVATE, page = 2)
        every { queries.list(query) } returns
            AdminFixtures.list((1..45).map { summary("Post $it", AdminPostStatus.DRAFT) }, query = query.page)

        val page = listPage("/admin/posts?status=draft&page=2")

        assertThat(page.rows).hasSize(20)
        assertThat(page.pagination.previousHref).isEqualTo("/admin/posts?status=private")
        assertThat(page.pagination.nextHref).isEqualTo("/admin/posts?status=private&page=3")
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

    private fun listPage(url: String): AdminPostsView = listPage(get(url))

    private fun listPage(request: MockHttpServletRequestBuilder): AdminPostsView =
        mockMvc
            .perform(request)
            .andExpect(status().isOk)
            .andExpect(view().name("admin/posts"))
            .andReturn()
            .modelOf("page")

    private fun query(
        group: AdminPostVisibility? = null,
        title: String = "",
        page: Int = 1,
    ) = AdminPostListQuery(group?.statuses.orEmpty(), title, PageQuery(page, 20))

    private fun summary(
        title: String,
        status: AdminPostStatus,
    ) = AdminFixtures.summary(title, status)
}
