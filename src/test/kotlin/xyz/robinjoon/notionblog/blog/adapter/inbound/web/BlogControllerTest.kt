package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
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
    private val pages = PostPageViewAssembler(clock)
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(BlogController(lists, posts, pages, HomePageViewAssembler(pages)))
            .setControllerAdvice(WebExceptionHandler())
            .build()
    private val category = CategoryId(UUID.nameUUIDFromBytes("category".toByteArray()))

    @Test
    fun `home lists published entries ten per page`() {
        every { lists.get(HomePageQuery(1)) } returns PostListLookupResult.Found(listPage(entries = 12, page = 1))

        val page = home("/")

        assertThat(page.list.entries).hasSize(10)
        assertThat(
            page.list.entries
                .first()
                .title,
        ).isEqualTo("Post 1")
        assertThat(page.list.pagination.nextHref).isEqualTo("/?page=2")
        assertThat(page.list.pagination.previousHref).isNull()
        assertThat(page.tabs.map { it.label to it.current }).containsExactly("글" to true, "시리즈" to false)
    }

    @Test
    fun `second home page holds the remainder and links back`() {
        every { lists.get(HomePageQuery(2)) } returns PostListLookupResult.Found(listPage(entries = 12, page = 2))

        val page = home("/?page=2")

        assertThat(page.list.entries).hasSize(2)
        assertThat(page.list.pagination.previousHref).isEqualTo("/")
        assertThat(page.list.pagination.nextHref).isNull()
    }

    @Test
    fun `a home page number below one is treated as the first page`() {
        every { lists.get(HomePageQuery(1)) } returns PostListLookupResult.Found(listPage(entries = 3, page = 1))

        assertThat(home("/?page=-4").list.pagination.page).isEqualTo(1)
    }

    @Test
    fun `tag filter is passed on and kept in pagination links`() {
        every { lists.get(HomePageQuery(1, category)) } returns
            PostListLookupResult.Found(listPage(entries = 12, page = 1).copy(selectedCategoryId = category))

        val page = home("/?tag=${category.value}")

        assertThat(page.list.pagination.nextHref).isEqualTo("/?tag=${category.value}&page=2")
        assertThat(page.search.categoryId).isEqualTo(category.value.toString())
    }

    @Test
    fun `the legacy category address answers like the tag address and links use the tag name`() {
        every { lists.get(HomePageQuery(1, category)) } returns
            PostListLookupResult.Found(listPage(entries = 12, page = 1).copy(selectedCategoryId = category))

        val page = home("/?category=${category.value}")

        assertThat(page.list.pagination.nextHref).isEqualTo("/?tag=${category.value}&page=2")
    }

    @Test
    fun `tag wins when both tag and legacy category are given`() {
        val other = CategoryId(UUID.nameUUIDFromBytes("other".toByteArray()))
        every { lists.get(HomePageQuery(1, category)) } returns
            PostListLookupResult.Found(listPage(entries = 3, page = 1).copy(selectedCategoryId = category))

        val page = home("/?tag=${category.value}&category=${other.value}")

        assertThat(page.search.categoryId).isEqualTo(category.value.toString())
    }

    @Test
    fun `search text is trimmed before the port and kept with the tag in every link`() {
        every { lists.get(HomePageQuery(1, category, "코틀린")) } returns
            PostListLookupResult.Found(listPage(entries = 12, page = 1).copy(selectedCategoryId = category, search = "코틀린"))

        val page = mockMvc.perform(get("/").param("tag", category.value.toString()).param("q", "  코틀린  ")).andReturn().home()

        assertThat(page.list.pagination.nextHref).isEqualTo("/?tag=${category.value}&q=%EC%BD%94%ED%8B%80%EB%A6%B0&page=2")
        assertThat(page.search.query).isEqualTo("  코틀린  ")
        assertThat(page.search.resultLabel).isEqualTo("검색 결과 12개")
        assertThat(page.search.error).isNull()
    }

    @Test
    fun `search punctuation is encoded so a plus sign survives a page link`() {
        every { lists.get(HomePageQuery(1, null, "c++ & a=b")) } returns
            PostListLookupResult.Found(listPage(entries = 12, page = 1).copy(search = "c++ & a=b"))

        val page = mockMvc.perform(get("/").param("q", "c++ & a=b")).andReturn().home()

        assertThat(page.list.pagination.nextHref).isEqualTo("/?q=c%2B%2B+%26+a%3Db&page=2")
    }

    @Test
    fun `a blank search is not applied`() {
        every { lists.get(HomePageQuery(1)) } returns PostListLookupResult.Found(listPage(entries = 3, page = 1))

        val page = mockMvc.perform(get("/").param("q", "   ")).andReturn().home()

        assertThat(page.search.resultLabel).isNull()
        assertThat(page.search.error).isNull()
    }

    @Test
    fun `a search of exactly the maximum length is applied unchanged`() {
        val text = "가".repeat(HomePageQuery.MAX_SEARCH_LENGTH)
        every { lists.get(HomePageQuery(1, null, text)) } returns
            PostListLookupResult.Found(listPage(entries = 1, page = 1).copy(search = text))

        val page = mockMvc.perform(get("/").param("q", text)).andReturn().home()

        assertThat(page.search.query).isEqualTo(text)
        assertThat(page.search.error).isNull()
    }

    @Test
    fun `a too long search is not cut but reported as 400 with the typed text and the unfiltered list`() {
        val text = "가".repeat(HomePageQuery.MAX_SEARCH_LENGTH + 1)
        every { lists.get(HomePageQuery(1)) } returns PostListLookupResult.Found(listPage(entries = 3, page = 1))

        val result =
            mockMvc
                .perform(get("/").param("q", text))
                .andExpect(status().isBadRequest)
                .andExpect(view().name("blog/home"))
                .andReturn()
        val page = result.modelOf<HomePageView>("page")

        assertThat(page.search.query).isEqualTo(text)
        assertThat(page.search.error).contains("100자")
        assertThat(page.search.resultLabel).isNull()
        assertThat(page.list.entries).hasSize(3)
    }

    @Test
    fun `an empty result with a search and a tag offers to clear the search or show every post`() {
        every { lists.get(HomePageQuery(1, category, "없는글")) } returns
            PostListLookupResult.Found(WebFixtures.listPage().copy(selectedCategoryId = category, search = "없는글"))

        val empty =
            checkNotNull(
                mockMvc
                    .perform(get("/").param("tag", category.value.toString()).param("q", "없는글"))
                    .andReturn()
                    .home()
                    .list.empty,
            )

        assertThat(empty.message).isEqualTo("조건에 맞는 공개 글이 없습니다.")
        assertThat(empty.actions.map { it.label to it.href })
            .containsExactly("검색어 지우기" to "/?tag=${category.value}", "전체 글 보기" to "/")
    }

    @Test
    fun `an empty tag without a search only offers every post`() {
        every { lists.get(HomePageQuery(1, category)) } returns
            PostListLookupResult.Found(WebFixtures.listPage().copy(selectedCategoryId = category))

        val empty = checkNotNull(home("/?tag=${category.value}").list.empty)

        assertThat(empty.message).isEqualTo("이 태그에는 아직 공개된 글이 없습니다.")
        assertThat(empty.actions.map { it.label }).containsExactly("전체 글 보기")
    }

    @Test
    fun `a blog without posts says so and has no action`() {
        every { lists.get(HomePageQuery(1)) } returns PostListLookupResult.Found(WebFixtures.listPage())

        val empty = checkNotNull(home("/").list.empty)

        assertThat(empty.message).isEqualTo("아직 공개된 글이 없습니다.")
        assertThat(empty.actions).isEmpty()
    }

    @Test
    fun `unknown or malformed tags are not found without asking the port`() {
        val unknown = CategoryId(UUID.nameUUIDFromBytes("unknown".toByteArray()))
        every { lists.get(HomePageQuery(1, unknown)) } returns PostListLookupResult.NotFound

        mockMvc.perform(get("/?tag=${unknown.value}")).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))
        mockMvc.perform(get("/?category=${unknown.value}")).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))
        mockMvc.perform(get("/?tag=not-a-uuid")).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))
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

    private fun home(url: String): HomePageView = mockMvc.perform(get(url)).andReturn().home()

    private fun MvcResult.home(): HomePageView {
        assertThat(response.status).isEqualTo(200)
        assertThat(modelAndView?.viewName).isEqualTo("blog/home")
        return modelOf("page")
    }

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
