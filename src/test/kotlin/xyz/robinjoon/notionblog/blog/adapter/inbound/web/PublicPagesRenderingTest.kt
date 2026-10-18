package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicCategoryCount
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// 공개 홈 목록과 글 속성 영역이 실제 템플릿에서 어떻게 보이는지 확인한다.
class PublicPagesRenderingTest {
    private val lists = mockk<GetPostListUseCase>()
    private val posts = mockk<GetPublicPostUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC)
    private val pages = PostPageViewAssembler(clock, "https://blog.example/feed.xml")
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(BlogController(lists, posts, pages, HomePageViewAssembler(pages)))
            .setViewResolvers(viewResolver())
            .build()

    @Test
    fun `home without published posts says so`() {
        every { lists.get(any()) } returns PostListLookupResult.Found(WebFixtures.listPage())

        assertThat(html("/")).contains("아직 공개된 글이 없습니다.", "RSS 구독").doesNotContain("nb-post-list")
    }

    @Test
    fun `home lists title summary and app tags instead of Notion tags and links to the public post`() {
        val entry =
            PostListEntry(
                WebFixtures.postId,
                WebFixtures.revisionId,
                PostProperties("<b>Title</b>", "Short summary", listOf("kotlin"), null),
                Instant.parse("2026-10-05T03:04:05Z"),
            )
        every { lists.get(any()) } returns PostListLookupResult.Found(WebFixtures.listPage(listOf(entry)))

        val html = html("/")

        assertThat(html).contains("&lt;b&gt;Title&lt;/b&gt;", "Short summary", "href=\"/posts/${WebFixtures.postId.value}\"")
        assertThat(html).doesNotContain("<b>Title", "kotlin")
    }

    @Test
    fun `post shows summary app tags and publication date above the body and never the Notion source tags`() {
        val revision =
            WebFixtures.revision(
                properties = PostProperties("Hello", "A summary", listOf("source-kotlin", "source-notion"), null),
            )
        val tag = ListedCategory(CategoryId(UUID.nameUUIDFromBytes("tag".toByteArray())), "<b>App tag</b>")
        every { posts.get(WebFixtures.postId) } returns
            PublicPostLookupResult.Found(WebFixtures.publicPage(revision).copy(categories = listOf(tag)))

        val html = html("/posts/${WebFixtures.postId.value}")

        assertThat(html).contains("A summary", "&lt;b&gt;App tag&lt;/b&gt;", "href=\"/?tag=${tag.id.value}\"", "발행일", "href=\"/\"")
        assertThat(html).doesNotContain("source-kotlin", "source-notion", "<b>App tag")
        assertThat(html.indexOf("A summary")).isLessThan(html.indexOf("RSS 구독"))
    }

    @Test
    fun `post in a series shows a collapsible contents list above the body and previous and next links below it`() {
        val revision = WebFixtures.revision(title = "Second")
        every { posts.get(WebFixtures.postId) } returns
            PublicPostLookupResult.Found(WebFixtures.publicPage(revision).copy(series = WebFixtures.seriesNavigation(currentIndex = 1)))

        val html = html("/posts/${WebFixtures.postId.value}")

        assertThat(
            html,
        ).contains("nb-series-toc", "시리즈 · &lt;DDD&gt; 적용기", "2 / 3", "시리즈 전체 보기", "href=\"/series/${WebFixtures.seriesId.value}\"")
        assertThat(html).contains("aria-current=\"page\"", "nb-series-pager", "이전 글", "다음 글")
        assertThat(
            html,
        ).contains("href=\"/posts/${WebFixtures.seriesPostId(0).value}\"", "href=\"/posts/${WebFixtures.seriesPostId(2).value}\"")
        assertThat(html.indexOf("nb-series-toc")).isLessThan(html.indexOf("nb-series-pager"))
    }

    @Test
    fun `first and last series posts only offer the neighbour that exists`() {
        every { posts.get(WebFixtures.postId) } returns
            PublicPostLookupResult.Found(
                WebFixtures.publicPage(WebFixtures.revision()).copy(series = WebFixtures.seriesNavigation(currentIndex = 0)),
            )
        val first = html("/posts/${WebFixtures.postId.value}")
        every { posts.get(WebFixtures.postId) } returns
            PublicPostLookupResult.Found(
                WebFixtures.publicPage(WebFixtures.revision()).copy(series = WebFixtures.seriesNavigation(currentIndex = 2)),
            )
        val last = html("/posts/${WebFixtures.postId.value}")

        assertThat(first).contains("다음 글").doesNotContain("이전 글")
        assertThat(last).contains("이전 글").doesNotContain("다음 글")
    }

    @Test
    fun `post outside a series renders no series markup`() {
        every { posts.get(WebFixtures.postId) } returns PublicPostLookupResult.Found(WebFixtures.publicPage(WebFixtures.revision()))

        assertThat(html("/posts/${WebFixtures.postId.value}")).doesNotContain("nb-series")
    }

    @Test
    fun `home shows tabs a search form that keeps the tag and escapes the typed text and tag navigation with counts`() {
        val dev = ListedCategory(CategoryId(UUID.nameUUIDFromBytes("dev".toByteArray())), "Dev")
        every { lists.get(any()) } returns
            PostListLookupResult.Found(
                WebFixtures.listPage().copy(
                    categories = listOf(PublicCategoryCount(dev, 2)),
                    selectedCategoryId = dev.id,
                    search = "<script>",
                    totalPublished = 5,
                ),
            )

        val html = htmlOf(get("/").param("tag", dev.id.value.toString()).param("q", "<script>"))

        assertThat(html).contains("nb-home-tabs", "href=\"/series\"", "role=\"search\"", "name=\"q\"", "value=\"&lt;script&gt;\"")
        assertThat(html).contains("name=\"tag\"", "value=\"${dev.id.value}\"", "nb-tag-nav", "전체", ">5<", ">2<", "검색 결과 0개")
        assertThat(html).contains("조건에 맞는 공개 글이 없습니다.", "검색어 지우기", "전체 글 보기").doesNotContain("<script>")
    }

    @Test
    fun `a rejected search is shown as an alert and keeps the typed text`() {
        every { lists.get(any()) } returns PostListLookupResult.Found(WebFixtures.listPage())
        val text = "가".repeat(101)

        val html = htmlOf(get("/").param("q", text))

        assertThat(html).contains("role=\"alert\"", "검색어는 100자 이하로 입력하세요.", "value=\"$text\"")
    }

    @Test
    fun `card layout shows the stored cover or the bundled thumbnail and keeps tag links escaped`() {
        val category = ListedCategory(CategoryId(UUID.nameUUIDFromBytes("c".toByteArray())), "<i>Dev</i>")
        val entry =
            PostListEntry(
                WebFixtures.postId,
                WebFixtures.revisionId,
                PostProperties("Hello", "Summary", emptyList(), MediaSource.External(URI("https://cdn.example/cover.png"))),
                Instant.parse("2026-10-05T03:04:05Z"),
                listOf(category),
            )
        val configuration = HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.NEWEST_FIRST, 10, HomePostDetails())
        every { lists.get(any()) } returns
            PostListLookupResult.Found(
                WebFixtures
                    .listPage(
                        listOf(entry),
                    ).copy(configuration = configuration, categories = listOf(PublicCategoryCount(category, 1))),
            )

        val html = html("/")

        assertThat(
            html,
        ).contains("nb-home-cards", "/ui/default-post-thumbnail.svg", "&lt;i&gt;Dev&lt;/i&gt;", "?tag=${category.id.value}")
        assertThat(html).doesNotContain("cdn.example", "<i>Dev").doesNotContain("소개 문서")
    }

    @Test
    fun `hidden details and tag menu disappear while the plain list has no thumbnails`() {
        val category = ListedCategory(CategoryId(UUID.nameUUIDFromBytes("c".toByteArray())), "Dev")
        val entry =
            PostListEntry(
                WebFixtures.postId,
                WebFixtures.revisionId,
                PostProperties("Hello", "Hidden summary", emptyList(), null),
                Instant.parse("2026-10-05T03:04:05Z"),
                listOf(category),
            )
        val configuration = HomeConfiguration.create(HomeLayout.LIST, HomePostOrder.NEWEST_FIRST, 10, HomePostDetails(false, false, false))
        every { lists.get(any()) } returns
            PostListLookupResult.Found(
                WebFixtures
                    .listPage(
                        listOf(entry),
                    ).copy(configuration = configuration, categories = listOf(PublicCategoryCount(category, 1))),
            )

        val html = html("/")

        assertThat(
            html,
        ).contains("Hello", "nb-home-plain").doesNotContain("Hidden summary", "Dev", "nb-post-thumb", "nb-tag-nav", "nb-category-badge")
        assertThat(html).contains("role=\"search\"")
    }

    private fun htmlOf(request: MockHttpServletRequestBuilder): String =
        mockMvc
            .perform(request)
            .andReturn()
            .response.contentAsString

    private fun html(url: String): String =
        mockMvc
            .perform(get(url))
            .andReturn()
            .response.contentAsString

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
