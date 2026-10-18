package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.AdminPostActions
import xyz.robinjoon.notionblog.blog.application.model.AdminPostHistory
import xyz.robinjoon.notionblog.blog.application.model.AdminPostListQuery
import xyz.robinjoon.notionblog.blog.application.model.AdminPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.AdminPostSummary
import xyz.robinjoon.notionblog.blog.application.model.CurrentClassification
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.application.model.SeriesMembership
import xyz.robinjoon.notionblog.blog.application.model.SourceHealth
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.fixture.SiteFixtures
import java.net.URI
import java.time.Instant
import java.util.UUID

class AdminPostQueryServiceTest {
    private val summaries = mockk<AdminPostSummaryService>()
    private val posts = mockk<PostRepository>()
    private val chromes = mockk<SitePresentationContextService>()
    private val references = mockk<ContentReferencesService>()
    private val failures = mockk<SnapshotFailureReporter>()
    private val service = AdminPostQueryService(summaries, posts, chromes, references, failures)
    private val noActions = AdminPostActions(null, false, true, false)
    private val classification =
        CurrentClassification(
            listOf(ListedCategory(CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000009")), "Kotlin")),
            SeriesMembership(SeriesId(UUID.fromString("00000000-0000-0000-0001-000000000001")), "DDD", 2, 3),
        )

    @Test
    fun `the list hides archived posts by default and filters by any of the chosen statuses and title`() {
        val all =
            listOf(
                summary(1, "Kotlin notes", AdminPostStatus.PUBLISHED, Instant.parse("2026-10-02T00:00:00Z")),
                summary(2, "Old kotlin", AdminPostStatus.ARCHIVED, Instant.parse("2026-10-03T00:00:00Z")),
                summary(3, "Spring", AdminPostStatus.DRAFT, null),
                summary(4, "kotlin draft", AdminPostStatus.DRAFT, Instant.parse("2026-10-04T00:00:00Z")),
            )
        givenList(all)

        val everything =
            service
                .list(AdminPostListQuery(emptySet(), "", PageQuery(1, 10)))
                .posts.items
                .map { it.postId }
        val kotlinDrafts = service.list(AdminPostListQuery(setOf(AdminPostStatus.DRAFT), "KOTLIN", PageQuery(1, 10))).posts.items
        val archived = service.list(AdminPostListQuery(setOf(AdminPostStatus.ARCHIVED), "", PageQuery(1, 10))).posts.items
        val publicOrArchived =
            service.list(AdminPostListQuery(setOf(AdminPostStatus.PUBLISHED, AdminPostStatus.ARCHIVED), "", PageQuery(1, 10))).posts.items

        assertThat(everything).containsExactly(id(3), id(4), id(1))
        assertThat(kotlinDrafts.map { it.postId }).containsExactly(id(4))
        assertThat(archived.map { it.postId }).containsExactly(id(2))
        assertThat(publicOrArchived.map { it.postId }).containsExactly(id(2), id(1))
    }

    @Test
    fun `the list is paged after filtering`() {
        givenList((1..5).map { summary(it, "Post $it", AdminPostStatus.DRAFT, Instant.parse("2026-10-0${it}T00:00:00Z")) })

        val page = service.list(AdminPostListQuery(emptySet(), "post", PageQuery(2, 2))).posts

        assertThat(page.items.map { it.postId }).containsExactly(id(3), id(2))
        assertThat(page.totalItems).isEqualTo(5)
        assertThat(page.totalPages).isEqualTo(3)
    }

    @Test
    fun `a managed post without an import offers no publish`() {
        val post = PostFixtures.privatePost(id(1))
        val summary = summary(1, "Untitled", AdminPostStatus.DRAFT, null)
        every { posts.find(id(1)) } returns post
        every { summaries.summarize(listOf(post)) } returns listOf(summary)
        every { summaries.actions(post, null) } returns noActions

        assertThat(service.get(id(1), PreviewVersion.LATEST)).isEqualTo(AdminPostLookupResult.NotImported(summary, noActions))
        assertThat(service.get(id(1), PreviewVersion.PUBLISHED)).isEqualTo(AdminPostLookupResult.NotFound)
    }

    @Test
    fun `a preview shows the chosen revision with its publication history`() {
        val published = PostFixtures.revisionId(1)
        val latest = PostFixtures.revisionId(2)
        val post = PostFixtures.publicPost(id(1), published = published, latest = latest)
        val revision = PostFixtures.revision(id(1), published)
        val summary = summary(1, "Title", AdminPostStatus.CHANGES_PENDING, PostFixtures.capturedAt)
        val chrome = SiteFixtures.chrome()
        every { posts.find(id(1)) } returns post
        every { summaries.summarize(listOf(post)) } returns listOf(summary)
        every { posts.findRevision(id(1), published) } returns revision
        every { chromes.load() } returns chrome
        every { references.resolve(listOf(revision)) } returns SiteFixtures.noReferences()
        every { summaries.actions(post, published) } returns noActions
        every { summaries.classification(post) } returns classification

        val result = service.get(id(1), PreviewVersion.PUBLISHED) as AdminPostLookupResult.Found

        assertThat(result.preview.revision).isEqualTo(revision)
        assertThat(result.preview.history).isEqualTo(AdminPostHistory(published, PostFixtures.capturedAt))
        assertThat(result.preview.actions).isEqualTo(noActions)
        assertThat(result.classification).isEqualTo(classification)
    }

    private fun givenList(all: List<AdminPostSummary>) {
        every { posts.findAllManaged() } returns emptyList()
        every { summaries.summarize(emptyList()) } returns all
    }

    private fun summary(
        label: Int,
        title: String,
        status: AdminPostStatus,
        capturedAt: Instant?,
    ) = AdminPostSummary(id(label), title, status, capturedAt, SourceHealth(URI("https://www.notion.so/x"), null, null), 0)

    private fun id(label: Int): PostId = PostFixtures.postId(label)
}
