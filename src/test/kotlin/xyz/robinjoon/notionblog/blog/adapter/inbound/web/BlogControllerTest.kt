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
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.HomePageView
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.HomePageQuery
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BlogControllerTest {
    private val lists = mockk<GetPostListUseCase>()
    private val posts = mockk<GetPublicPostUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC)
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(BlogController(lists, posts, PostPageViewAssembler(clock)))
            .setControllerAdvice(WebExceptionHandler())
            .build()

    @Test
    fun `home lists published entries ten per page`() {
        every { lists.get(HomePageQuery(1)) } returns PostListLookupResult.Found(listPage(entries = 12, page = 1))

        val page = home("/")

        assertThat(page.entries).hasSize(10)
        assertThat(page.entries.first().title).isEqualTo("Post 1")
        assertThat(page.pagination.nextHref).isEqualTo("/?page=2")
        assertThat(page.pagination.previousHref).isNull()
    }

    @Test
    fun `second home page holds the remainder and links back`() {
        every { lists.get(HomePageQuery(2)) } returns PostListLookupResult.Found(listPage(entries = 12, page = 2))

        val page = home("/?page=2")

        assertThat(page.entries).hasSize(2)
        assertThat(page.pagination.previousHref).isEqualTo("/")
        assertThat(page.pagination.nextHref).isNull()
    }

    @Test
    fun `a home page number below one is treated as the first page`() {
        every { lists.get(HomePageQuery(1)) } returns PostListLookupResult.Found(listPage(entries = 3, page = 1))

        assertThat(home("/?page=-4").pagination.page).isEqualTo(1)
    }

    @Test
    fun `category filter is passed on and kept in pagination links`() {
        val category = CategoryId(UUID.nameUUIDFromBytes("category".toByteArray()))
        every { lists.get(HomePageQuery(1, category)) } returns
            PostListLookupResult.Found(listPage(entries = 12, page = 1).copy(selectedCategoryId = category))

        val page = home("/?category=${category.value}")

        assertThat(page.pagination.nextHref).isEqualTo("/?category=${category.value}&page=2")
    }

    @Test
    fun `unknown or malformed category is not found`() {
        val unknown = CategoryId(UUID.nameUUIDFromBytes("unknown".toByteArray()))
        every { lists.get(HomePageQuery(1, unknown)) } returns PostListLookupResult.NotFound

        mockMvc.perform(get("/?category=${unknown.value}")).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))
        mockMvc.perform(get("/?category=not-a-uuid")).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))
    }

    @Test
    fun `unavailable home maps to the unavailable view`() {
        every { lists.get(HomePageQuery(1)) } returns PostListLookupResult.ContentUnavailable

        mockMvc
            .perform(get("/"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(view().name("error/blog-unavailable"))
    }

    @Test
    fun `post found is rendered as post view`() {
        every { posts.get(WebFixtures.postId) } returns
            PublicPostLookupResult.Found(WebFixtures.publicPage(WebFixtures.revision(title = "Hello")))

        mockMvc
            .perform(get("/posts/${WebFixtures.postId.value}"))
            .andExpect(status().isOk)
            .andExpect(view().name("blog/post"))
    }

    @Test
    fun `not found and unavailable posts map to dedicated error views`() {
        every { posts.get(WebFixtures.postId) } returns PublicPostLookupResult.NotFound
        mockMvc
            .perform(get("/posts/${WebFixtures.postId.value}"))
            .andExpect(status().isNotFound)
            .andExpect(view().name("error/blog-not-found"))

        every { posts.get(WebFixtures.postId) } returns PublicPostLookupResult.ContentUnavailable
        mockMvc
            .perform(get("/posts/${WebFixtures.postId.value}"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(view().name("error/blog-unavailable"))
    }

    @Test
    fun `malformed post id is not found`() {
        mockMvc
            .perform(get("/posts/not-a-uuid"))
            .andExpect(status().isNotFound)
            .andExpect(view().name("error/blog-not-found"))
    }

    private fun home(url: String): HomePageView =
        mockMvc
            .perform(get(url))
            .andExpect(status().isOk)
            .andExpect(view().name("blog/home"))
            .andReturn()
            .modelOf("page")

    private fun listPage(
        entries: Int,
        page: Int,
    ): PostListPage =
        PostListPage(
            WebFixtures.chrome(),
            ResultPage.of(
                (1..entries).map { index ->
                    PostListEntry(
                        PostId(UUID.nameUUIDFromBytes("post-$index".toByteArray())),
                        WebFixtures.revisionId,
                        PostProperties("Post $index", "Summary $index", listOf("kotlin"), null),
                        Instant.parse("2026-10-05T03:04:05Z"),
                    )
                },
                PageQuery(page, 10),
            ),
            ContentReferences(emptyMap(), RevisionMedia.empty),
        )
}
