package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.AdminPostActions
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.CurrentClassification
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PostRevisionSummary
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.SeriesMembership
import xyz.robinjoon.notionblog.blog.application.model.SourceHealth
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostCatalog
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailure
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.blog.domain.sync.SyncState
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.net.URI
import java.util.UUID

class AdminPostSummaryServiceTest {
    private val posts = mockk<PostRepository>()
    private val syncStates = mockk<SyncStateRepository>()
    private val catalog = mockk<PostCatalog>()
    private val categories = mockk<CategoryRepository>()
    private val series = mockk<SeriesRepository>()
    private val service = AdminPostSummaryService(posts, syncStates, catalog, categories, series)
    private val first = PostFixtures.revisionId(1)
    private val second = PostFixtures.revisionId(2)

    @Test
    fun `status comes from the stored publication only`() {
        val draft = PostFixtures.privatePost(PostFixtures.postId(1), latest = first)
        val published = PostFixtures.publicPost(PostFixtures.postId(2), published = first)
        val pending = PostFixtures.publicPost(PostFixtures.postId(3), published = first, latest = second)
        val unpublished = PostFixtures.publicPost(PostFixtures.postId(4), published = first).unpublish()
        val archived = PostFixtures.publicPost(PostFixtures.postId(5), published = first).archive()
        givenStores(listOf(draft, published, pending, unpublished, archived))

        val statuses = service.summarize(listOf(draft, published, pending, unpublished, archived)).map { it.status }

        assertThat(statuses).containsExactly(
            AdminPostStatus.DRAFT,
            AdminPostStatus.PUBLISHED,
            AdminPostStatus.CHANGES_PENDING,
            AdminPostStatus.UNPUBLISHED,
            AdminPostStatus.ARCHIVED,
        )
    }

    @Test
    fun `a summary shows the title, import time, source health and missing files of the published revision`() {
        val post = PostFixtures.publicPost(PostFixtures.postId(1), published = first, latest = second)
        givenStores(listOf(post))
        every { posts.findUnboundMedia(setOf(second, first)) } returns mapOf(first to setOf(PostFixtures.hosted("legacy.png")))
        val target = SyncTarget.Post(post.id)
        val failing = SyncState.restore(target, null, PostFixtures.capturedAt, SyncFailure(2, SyncFailureKind.ACCESS))
        every { syncStates.findAll(setOf(target)) } returns mapOf(target to failing)

        val summary = service.summarize(listOf(post)).single()

        assertThat(summary.title).isEqualTo("Stored title")
        assertThat(summary.latestCapturedAt).isEqualTo(PostFixtures.capturedAt)
        assertThat(summary.source).isEqualTo(SourceHealth(URI("https://www.notion.so/doc"), null, SyncFailureKind.ACCESS))
        assertThat(summary.missingMedia).isEqualTo(1)
    }

    @Test
    fun `publish is offered only for the shown latest revision that readers do not already get`() {
        val draft = PostFixtures.privatePost(PostFixtures.postId(1), latest = second, generation = 7)
        val current = PostFixtures.publicPost(PostFixtures.postId(2), published = second)
        val archived = draft.archive()

        assertThat(service.actions(draft, second)).isEqualTo(AdminPostActions(PublishPostCommand(draft.id, second, 7), false, true, false))
        assertThat(service.actions(draft, first).publish).isNull()
        assertThat(service.actions(current, second)).isEqualTo(AdminPostActions(null, true, true, false))
        assertThat(service.actions(archived, second)).isEqualTo(AdminPostActions(null, false, false, true))
    }

    @Test
    fun `the current classification lists only assigned tags in display order and the full-membership series position`() {
        val kotlin = Category.restore(CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000011")), "Kotlin", 0)
        val spring = Category.restore(CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000012")), "Spring", 1)
        val unused = Category.restore(CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000013")), "Unused", 2)
        val post = PostFixtures.privatePost(PostFixtures.postId(2)).categorize(setOf(spring.id, kotlin.id))
        val seriesId = SeriesId(UUID.fromString("00000000-0000-0000-0001-000000000001"))
        val ddd = Series.restore(seriesId, "DDD", "", listOf(PostFixtures.postId(1), post.id, PostFixtures.postId(3)))
        every { categories.findAll() } returns listOf(kotlin, spring, unused)
        every { series.findAll() } returns listOf(ddd)

        val classification = service.classification(post)

        assertThat(classification).isEqualTo(
            CurrentClassification(
                listOf(ListedCategory(kotlin.id, "Kotlin"), ListedCategory(spring.id, "Spring")),
                SeriesMembership(seriesId, "DDD", 2, 3),
            ),
        )
        every { series.findAll() } returns emptyList()
        assertThat(service.classification(post).series).isNull()
    }

    private fun givenStores(managed: List<Post>) {
        val shown = managed.flatMapTo(linkedSetOf()) { listOfNotNull(it.latestRevisionId, it.publication.publishedRevisionId) }
        every { posts.findRevisionSummaries(shown) } returns
            shown.associateWith { PostRevisionSummary(it, "Revision", SourceRevision("rev"), PostFixtures.capturedAt) }
        every { posts.findUnboundMedia(shown) } returns emptyMap()
        every { posts.findTitles(managed.mapTo(linkedSetOf()) { it.id }) } returns managed.associate { it.id to "Stored title" }
        every { syncStates.findAll(managed.mapTo(linkedSetOf()) { SyncTarget.Post(it.id) }) } returns emptyMap()
        every { catalog.editUrl(any()) } returns URI("https://www.notion.so/doc")
    }
}
