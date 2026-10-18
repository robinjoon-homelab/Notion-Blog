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
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicSeries
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesSummary
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicSeriesUseCase
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// 공개 시리즈 목록과 시리즈 안의 순서 목록이 상태 코드와 실제 템플릿에서 어떻게 보이는지 확인한다.
class SeriesPagesTest {
    private val series = mockk<GetPublicSeriesUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC)
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(SeriesController(series, SeriesPageViewAssembler(PostPageViewAssembler(clock))))
            .setControllerAdvice(WebExceptionHandler())
            .setViewResolvers(viewResolver())
            .build()

    @Test
    fun `list shows a card per public series with the escaped title description count and the series tab`() {
        every { series.list() } returns
            PublicSeriesListLookupResult.Found(
                PublicSeriesListPage(
                    WebFixtures.chrome(),
                    listOf(PublicSeriesSummary(WebFixtures.seriesId, "<DDD> 적용기", "차근차근 적용합니다", 3, entry(0))),
                    ContentReferences(emptyMap(), RevisionMedia.empty),
                ),
            )

        val html = html("/series")

        assertThat(html).contains("&lt;DDD&gt; 적용기", "차근차근 적용합니다", "글 3개", "href=\"/series/${WebFixtures.seriesId.value}\"")
        assertThat(html).contains("aria-current=\"page\"", "thumbnail-fallback.js").doesNotContain("<DDD>")
    }

    @Test
    fun `a series card uses the stored cover of the first public post and otherwise the bundled thumbnail`() {
        val source = MediaSource.SourceHosted(URI("https://files.notion.so/cover.png"), null)
        val stored = MediaKey("a".repeat(MEDIA_KEY_LENGTH))
        val withCover = entry(0, source)
        val references =
            ContentReferences(
                emptyMap(),
                RevisionMedia(
                    mapOf(withCover.revisionId to mapOf(SourceMediaKey.of(source.url) to stored)),
                ),
            )
        every { series.list() } returns
            PublicSeriesListLookupResult.Found(
                PublicSeriesListPage(
                    WebFixtures.chrome(),
                    listOf(
                        PublicSeriesSummary(WebFixtures.seriesId, "With cover", "", 1, withCover),
                        PublicSeriesSummary(
                            WebFixtures.seriesId,
                            "Remote cover",
                            "",
                            1,
                            entry(1, MediaSource.External(URI("https://cdn.example/x.png"))),
                        ),
                    ),
                    references,
                ),
            )

        val html = html("/series")

        assertThat(html).contains("src=\"/media/${stored.value}\"", "src=\"/ui/default-post-thumbnail.svg\"").doesNotContain("cdn.example")
    }

    @Test
    fun `list without public series says so and links back to every post`() {
        every { series.list() } returns
            PublicSeriesListLookupResult.Found(
                PublicSeriesListPage(WebFixtures.chrome(), emptyList(), ContentReferences(emptyMap(), RevisionMedia.empty)),
            )

        assertThat(html("/series")).contains("아직 공개된 시리즈가 없습니다.", "전체 글 보기").doesNotContain("nb-series-grid")
    }

    @Test
    fun `list of an uninitialized blog is unavailable`() {
        every { series.list() } returns PublicSeriesListLookupResult.ContentUnavailable

        mockMvc.perform(get("/series")).andExpect(status().isServiceUnavailable).andExpect(view().name("error/blog-unavailable"))
    }

    @Test
    fun `detail lists public posts in the series order with ordinals regardless of the home order setting`() {
        every { series.get(WebFixtures.seriesId) } returns
            PublicSeriesLookupResult.Found(
                seriesPage(HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.OLDEST_FIRST, 10, HomePostDetails())),
            )

        val html = html("/series/${WebFixtures.seriesId.value}")

        assertThat(html).contains("&lt;DDD&gt; 적용기", "설명입니다", "href=\"/series\"")
        assertThat(listOf("Chapter 1", "Chapter 2", "Chapter 3").map(html::indexOf)).isSorted().doesNotContain(-1)
        assertThat(
            html,
        ).contains("href=\"/posts/${WebFixtures.seriesPostId(0).value}\"", "Summary 1", "Oct 5, 2026").doesNotContain("nb-post-thumb")
    }

    @Test
    fun `detail hides summaries and dates when the home settings hide them`() {
        every { series.get(WebFixtures.seriesId) } returns
            PublicSeriesLookupResult.Found(
                seriesPage(HomeConfiguration.create(HomeLayout.LIST, HomePostOrder.NEWEST_FIRST, 10, HomePostDetails(false, false, false))),
            )

        assertThat(html("/series/${WebFixtures.seriesId.value}")).contains("Chapter 1").doesNotContain("Summary 1", "Oct 5, 2026")
    }

    @Test
    fun `unknown or malformed series are not found and an uninitialized blog is unavailable`() {
        every { series.get(WebFixtures.seriesId) } returns PublicSeriesLookupResult.NotFound
        mockMvc
            .perform(
                get("/series/${WebFixtures.seriesId.value}"),
            ).andExpect(status().isNotFound)
            .andExpect(view().name("error/blog-not-found"))
        mockMvc.perform(get("/series/not-a-uuid")).andExpect(status().isNotFound).andExpect(view().name("error/blog-not-found"))

        every { series.get(WebFixtures.seriesId) } returns PublicSeriesLookupResult.ContentUnavailable
        mockMvc
            .perform(
                get("/series/${WebFixtures.seriesId.value}"),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(view().name("error/blog-unavailable"))
    }

    private fun seriesPage(configuration: HomeConfiguration): PublicSeriesPage =
        PublicSeriesPage(
            WebFixtures.chrome(),
            PublicSeries(WebFixtures.seriesId, "<DDD> 적용기", "설명입니다", (0..2).map { entry(it) }),
            ContentReferences(emptyMap(), RevisionMedia.empty),
            configuration,
        )

    private fun entry(
        index: Int,
        cover: MediaSource? = null,
    ): PostListEntry =
        PostListEntry(
            WebFixtures.seriesPostId(index),
            PostRevisionId(UUID.nameUUIDFromBytes("revision-$index".toByteArray())),
            PostProperties("Chapter ${index + 1}", "Summary ${index + 1}", emptyList(), cover),
            Instant.parse("2026-10-05T03:04:05Z"),
        )

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

    private companion object {
        const val MEDIA_KEY_LENGTH = 64
    }
}
