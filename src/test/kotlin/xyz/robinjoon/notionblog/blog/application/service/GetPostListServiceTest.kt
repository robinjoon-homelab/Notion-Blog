package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.HomePageQuery
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicCategoryCount
import xyz.robinjoon.notionblog.blog.application.model.PublishedListQuery
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.fixture.SiteFixtures

class GetPostListServiceTest {
    private val chromes = mockk<SitePresentationContextService>()
    private val references = mockk<ContentReferencesService>()
    private val published = mockk<PublishedPostStore>()
    private val failures = mockk<SnapshotFailureReporter>()
    private val homes = mockk<HomeConfigurationRepository>()
    private val categories = mockk<CategoryRepository>()
    private val service = GetPostListService(chromes, references, published, failures, homes, categories)
    private val chrome = SiteFixtures.chrome()
    private val query = HomePageQuery(1)
    private val storedQuery = PublishedListQuery(PageQuery(1, 10))
    private val excluded = setOf(PostFixtures.postId(9))

    @Test
    fun `lists public posts without site documents and resolves each cover through its own revision`() {
        val cover = PostFixtures.hosted("cover.png")
        val first = entry(1, cover)
        val second = entry(2, cover)
        val name = SourceMediaKey.of(cover.url)
        val covers =
            RevisionMedia(mapOf(first.revisionId to mapOf(name to key('a')), second.revisionId to mapOf(name to key('b'))))
        givenChrome()
        every { published.findListPage(excluded, storedQuery) } returns ResultPage(listOf(first, second), 1, 10, 2)
        every { references.media(setOf(first.revisionId, second.revisionId)) } returns covers

        val found = (service.get(query) as PostListLookupResult.Found).page

        assertThat(found.entries.items).containsExactly(first, second)
        assertThat(found.configuration).isEqualTo(HomeConfiguration.defaults())
        assertThat(found.references.media.keyFor(first.revisionId, cover)).isEqualTo(key('a'))
        assertThat(found.references.media.keyFor(second.revisionId, cover)).isEqualTo(key('b'))
    }

    private fun entry(
        label: Int,
        cover: MediaSource.SourceHosted,
    ) = PostListEntry(
        PostFixtures.postId(label),
        PostFixtures.revisionId(label),
        PostFixtures.properties(cover = cover),
        PostFixtures.capturedAt,
    )

    private fun key(digit: Char) = MediaKey(digit.toString().repeat(64))

    @Test
    fun `the list is unavailable without a site or when stored properties cannot be read`() {
        every { chromes.load() } returns null

        assertThat(service.get(query)).isEqualTo(PostListLookupResult.ContentUnavailable)

        givenChrome()
        every { published.findListPage(excluded, storedQuery) } throws SnapshotContentException("broken")
        every { failures.report(any(), any(), any()) } just Runs

        assertThat(service.get(query)).isEqualTo(PostListLookupResult.ContentUnavailable)
    }

    @Test
    fun `저장된 홈 설정과 카테고리 필터로 조회한 결과를 반환한다`() {
        givenChrome()
        val category = Category.create(CategoryId(PostFixtures.postId(30).value), "Kotlin", 0)
        val configuration = HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.OLDEST_FIRST, 2, HomePostDetails(false, true, true))
        val categoryCounts = listOf(PublicCategoryCount(ListedCategory(category.id, category.name), 3))
        val first = entry(1, PostFixtures.hosted("first.png"))
        val filtered = PublishedListQuery(PageQuery(2, 2), HomePostOrder.OLDEST_FIRST, category.id)
        every { homes.find() } returns configuration
        every { categories.find(category.id) } returns category
        every { published.findListPage(excluded, filtered) } returns ResultPage(listOf(first), 2, 2, 3)
        every { published.findCategoryCounts(excluded) } returns categoryCounts
        every { references.media(setOf(first.revisionId)) } returns RevisionMedia.empty

        val found = (service.get(HomePageQuery(2, category.id)) as PostListLookupResult.Found).page

        assertThat(found.configuration).isEqualTo(configuration)
        assertThat(found.entries.items).containsExactly(first)
        assertThat(found.entries.number).isEqualTo(2)
        assertThat(found.entries.size).isEqualTo(2)
        assertThat(found.categories).isEqualTo(categoryCounts)
        assertThat(found.selectedCategoryId).isEqualTo(category.id)
    }

    @Test
    fun `검색어로 조회한 공개 글과 함께 검색어와 태그·검색과 무관한 전체 공개 글 수를 반환한다`() {
        givenChrome()
        val first = entry(1, PostFixtures.hosted("first.png"))
        val searched = PublishedListQuery(PageQuery(1, 10), search = "kotlin")
        every { published.findListPage(excluded, searched) } returns ResultPage(listOf(first), 1, 10, 1)
        every { references.media(setOf(first.revisionId)) } returns RevisionMedia.empty

        val found = (service.get(HomePageQuery(1, search = "kotlin")) as PostListLookupResult.Found).page

        assertThat(found.entries.items).containsExactly(first)
        assertThat(found.search).isEqualTo("kotlin")
        assertThat(found.totalPublished).isEqualTo(7)
    }

    @Test
    fun `없는 카테고리는 전체 글 목록으로 바꾸지 않고 찾을 수 없음으로 반환한다`() {
        givenChrome()
        val categoryId = CategoryId(PostFixtures.postId(30).value)
        every { categories.find(categoryId) } returns null

        assertThat(service.get(HomePageQuery(1, categoryId))).isEqualTo(PostListLookupResult.NotFound)
    }

    private fun givenChrome() {
        every { chromes.load() } returns chrome
        every { chromes.siteDocumentPostIds(chrome.site) } returns excluded
        every { references.resolve(emptyList()) } returns SiteFixtures.noReferences()
        every { homes.find() } returns null
        every { published.findCategoryCounts(excluded) } returns emptyList()
        every { published.countListed(excluded) } returns 7
    }
}
