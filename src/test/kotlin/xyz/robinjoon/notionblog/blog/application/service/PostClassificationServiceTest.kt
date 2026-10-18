package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.application.model.PostClassification
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.application.model.SourceTagCandidate
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesRepository
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.util.UUID

class PostClassificationServiceTest {
    private val posts = mockk<PostRepository>()
    private val categories = mockk<CategoryRepository>()
    private val series = mockk<SeriesRepository>()
    private val service = PostClassificationService(posts, categories, series) { categoryId(9) }
    private val postId = PostFixtures.postId(1)
    private val publishedRevision = PostFixtures.revisionId(1)
    private val latestRevision = PostFixtures.revisionId(2)
    private val kotlin = Category.restore(categoryId(1), "Kotlin", 0)
    private val spring = Category.restore(categoryId(2), "Spring", 3)
    private val published =
        PostFixtures.publicPost(postId, published = publishedRevision, latest = latestRevision).categorize(setOf(kotlin.id))
    private val ddd = Series.restore(seriesId(1), "DDD", "", listOf(postId))
    private val notes = Series.restore(seriesId(2), "Notes", "", emptyList())

    @BeforeEach
    fun givenStoredState() {
        every { posts.find(postId) } returns published
        every { posts.lock(postId) } returns published
        every { posts.findTitles(setOf(postId)) } returns mapOf(postId to "Post title")
        every { posts.findAllManaged() } returns listOf(published)
        every { posts.findRevision(postId, publishedRevision) } returns revision(publishedRevision, " kotlin ", "Architecture", "kotlin")
        every { posts.findRevision(postId, latestRevision) } returns revision(latestRevision, "kotlin", "Unpublished")
        every { posts.saveCategories(any()) } just Runs
        every { categories.findAll() } returns listOf(kotlin, spring)
        every { categories.assignmentCounts() } returns mapOf(kotlin.id to 1)
        every { categories.save(any()) } just Runs
        every { series.findAll() } returns listOf(ddd, notes)
        every { series.save(any()) } just Runs
    }

    @Test
    fun `a public post offers the published revision's source tags matched to existing tags regardless of case`() {
        val expected =
            PostClassification(
                postId = postId,
                title = "Post title",
                categories = listOf(CategoryOverview(kotlin.id, "Kotlin", 0, 1), CategoryOverview(spring.id, "Spring", 3, 0)),
                selectedCategoryIds = setOf(kotlin.id),
                series = listOf(SeriesOverview(ddd.id, "DDD", "", 1, 1), SeriesOverview(notes.id, "Notes", "", 0, 0)),
                selectedSeriesId = ddd.id,
                sourceTags = listOf(SourceTagCandidate("kotlin", kotlin.id, true), SourceTagCandidate("Architecture", null, false)),
            )

        assertThat(service.get(postId)).isEqualTo(PostClassificationLookupResult.Found(expected))
    }

    @Test
    fun `a private post offers the latest revision's source tags`() {
        val draft = PostFixtures.privatePost(postId, latest = latestRevision)
        every { posts.find(postId) } returns draft

        val found = service.get(postId) as PostClassificationLookupResult.Found

        assertThat(found.classification.sourceTags.map(SourceTagCandidate::name)).containsExactly("kotlin", "Unpublished")
    }

    @Test
    fun `an unmanaged post has no classification and cannot be saved`() {
        val unknown = PostFixtures.postId(2)
        every { posts.find(unknown) } returns null

        assertThat(service.get(unknown)).isEqualTo(PostClassificationLookupResult.NotFound)
        assertThat(service.save(unknown, emptySet(), null, emptySet())).isEqualTo(PostClassificationChangeResult.PostNotFound)
    }

    @Test
    fun `tags, a move to another series and adopted source tags are saved together`() {
        every { categories.lock(setOf(kotlin.id, spring.id)) } returns listOf(kotlin, spring)
        every { series.lock(setOf(ddd.id, notes.id)) } returns listOf(ddd, notes)

        val result = service.save(postId, setOf(spring.id), notes.id, setOf(" kotlin", "Architecture"))

        assertThat(result).isEqualTo(PostClassificationChangeResult.Saved)
    }

    @Test
    fun `clearing every tag and leaving the series is saved`() {
        every { categories.lock(emptySet()) } returns emptyList()
        every { series.lock(setOf(ddd.id)) } returns listOf(ddd)

        assertThat(service.save(postId, emptySet(), null, emptySet())).isEqualTo(PostClassificationChangeResult.Saved)
    }

    @Test
    fun `a deleted tag or a missing series rejects the whole save`() {
        val deleted = categoryId(3)
        every { categories.lock(setOf(deleted)) } returns emptyList()
        every { categories.lock(emptySet()) } returns emptyList()
        every { series.lock(setOf(ddd.id, seriesId(3))) } returns listOf(ddd)

        assertThat(service.save(postId, setOf(deleted), ddd.id, emptySet())).isEqualTo(PostClassificationChangeResult.CategoryNotFound)
        assertThat(service.save(postId, emptySet(), seriesId(3), emptySet())).isEqualTo(PostClassificationChangeResult.SeriesNotFound)
    }

    @Test
    fun `a source tag that is only in the unpublished latest revision is not available for a public post`() {
        every { categories.lock(emptySet()) } returns emptyList()

        val result = service.save(postId, emptySet(), ddd.id, setOf("Unpublished"))

        assertThat(result).isEqualTo(PostClassificationChangeResult.SourceTagNotAvailable)
    }

    @Test
    fun `source tags are checked against the post as it is once locked`() {
        val draft = PostFixtures.privatePost(postId, latest = latestRevision)
        every { posts.find(postId) } returns draft
        every { categories.lock(emptySet()) } returns emptyList()

        val result = service.save(postId, emptySet(), ddd.id, setOf("Unpublished"))

        assertThat(result).isEqualTo(PostClassificationChangeResult.SourceTagNotAvailable)
    }

    @Test
    fun `a source tag longer than a tag name is shown but cannot be adopted`() {
        val longName = "x".repeat(Category.MAX_NAME_LENGTH + 1)
        every { posts.findRevision(postId, publishedRevision) } returns revision(publishedRevision, longName)
        every { categories.lock(emptySet()) } returns emptyList()

        val found = service.get(postId) as PostClassificationLookupResult.Found

        assertThat(found.classification.sourceTags.map(SourceTagCandidate::name)).containsExactly(longName)
        assertThat(
            service.save(postId, emptySet(), ddd.id, setOf(longName)),
        ).isEqualTo(PostClassificationChangeResult.SourceTagNotAvailable)
    }

    private fun revision(
        revisionId: PostRevisionId,
        vararg tags: String,
    ): PostRevision = PostFixtures.revision(postId, revisionId).copy(properties = PostProperties("Title", "Summary", tags.toList(), null))

    private fun categoryId(label: Int): CategoryId = CategoryId(UUID.fromString("00000000-0000-0000-0000-%012d".format(label)))

    private fun seriesId(label: Int): SeriesId = SeriesId(UUID.fromString("00000000-0000-0000-0001-%012d".format(label)))
}
