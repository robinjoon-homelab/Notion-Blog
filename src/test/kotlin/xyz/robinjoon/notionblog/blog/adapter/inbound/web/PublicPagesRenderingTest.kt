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
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(BlogController(lists, posts, PostPageViewAssembler(clock, "https://blog.example/feed.xml")))
            .setViewResolvers(viewResolver())
            .build()

    @Test
    fun `home without published posts says so`() {
        every { lists.get(any()) } returns PostListLookupResult.Found(WebFixtures.listPage())

        assertThat(html("/")).contains("아직 공개된 글이 없습니다.", "RSS 구독").doesNotContain("nb-post-list")
    }

    @Test
    fun `home lists title summary categories instead of Notion tags and links to the public post`() {
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
    fun `post shows summary tags and publication date above the body`() {
        val revision = WebFixtures.revision(properties = PostProperties("Hello", "A summary", listOf("kotlin", "notion"), null))
        every { posts.get(WebFixtures.postId) } returns PublicPostLookupResult.Found(WebFixtures.publicPage(revision))

        val html = html("/posts/${WebFixtures.postId.value}")

        assertThat(html).contains("A summary", "kotlin", "notion", "href=\"/\"")
        assertThat(html.indexOf("A summary")).isLessThan(html.indexOf("RSS 구독"))
    }

    @Test
    fun `card layout shows the stored cover or the bundled thumbnail and keeps category links escaped`() {
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
        ).contains("nb-home-cards", "/ui/default-post-thumbnail.svg", "&lt;i&gt;Dev&lt;/i&gt;", "?category=${category.id.value}")
        assertThat(html).doesNotContain("cdn.example", "<i>Dev").doesNotContain("소개 문서")
    }

    @Test
    fun `hidden details and category menu disappear while the plain list has no thumbnails`() {
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

        assertThat(html).contains("Hello", "nb-home-plain").doesNotContain("Hidden summary", "Dev", "nb-post-thumb", "카테고리")
    }

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
