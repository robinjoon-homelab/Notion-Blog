package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.AdminPostHistory
import xyz.robinjoon.notionblog.blog.application.model.AdminPostList
import xyz.robinjoon.notionblog.blog.application.model.AdminPostListQuery
import xyz.robinjoon.notionblog.blog.application.model.AdminPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.AdminPostPreview
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.AdminPostSummary
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.port.input.AdminPostQueryUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import java.time.Instant

@Transactional(readOnly = true)
class AdminPostQueryService(
    private val summaries: AdminPostSummaryService,
    private val posts: PostRepository,
    private val chromes: SiteChromeService,
    private val references: ContentReferencesService,
    private val snapshotFailures: SnapshotFailureReporter,
) : AdminPostQueryUseCase {
    @Transactional(readOnly = true)
    override fun list(query: AdminPostListQuery): AdminPostList {
        val matching =
            summaries
                .summarize(posts.findAllManaged())
                .filter { summary -> query.matches(summary) }
                .sortedWith(importOrder)
        return AdminPostList(ResultPage.of(matching, query.page))
    }

    @Transactional(readOnly = true)
    override fun get(
        postId: PostId,
        version: PreviewVersion,
    ): AdminPostLookupResult {
        val post = posts.find(postId) ?: return AdminPostLookupResult.NotFound
        val summary = summaries.summarize(listOf(post)).single()
        val revisionId =
            when (version) {
                PreviewVersion.LATEST -> {
                    post.latestRevisionId ?: return AdminPostLookupResult.NotImported(summary, summaries.actions(post, null))
                }

                PreviewVersion.PUBLISHED -> {
                    post.publication.publishedRevisionId ?: return AdminPostLookupResult.NotFound
                }
            }
        val revision = load(post, revisionId) ?: return AdminPostLookupResult.ContentUnavailable
        val chrome = chromes.load() ?: return AdminPostLookupResult.ContentUnavailable
        val shown = references.resolve(listOfNotNull(revision, chrome.header, chrome.footer))
        val history = AdminPostHistory(post.publication.publishedRevisionId, post.firstPublishedAt)
        val actions = summaries.actions(post, revision.id)
        val preview = AdminPostPreview(summary, chrome, revision, shown, actions, history)
        return AdminPostLookupResult.Found(preview, summaries.classification(post))
    }

    private fun load(
        post: Post,
        revisionId: PostRevisionId,
    ): PostRevision? =
        try {
            posts.findRevision(post.id, revisionId)
        } catch (failure: SnapshotContentException) {
            snapshotFailures.report(failure, SnapshotFailureOperation.ADMIN_PREVIEW, post.id)
            null
        }

    private fun AdminPostListQuery.matches(summary: AdminPostSummary): Boolean {
        val statusMatches = if (statuses.isEmpty()) summary.status != AdminPostStatus.ARCHIVED else summary.status in statuses
        return statusMatches && summary.title.contains(title.trim(), ignoreCase = true)
    }

    private companion object {
        // 가져온 적 없는 글을 먼저, 그다음 최근에 가져온 글 순서로 둔다.
        val importOrder: Comparator<AdminPostSummary> =
            compareBy<AdminPostSummary, Instant?>(nullsFirst(reverseOrder())) { it.latestCapturedAt }.thenBy { it.postId.value }
    }
}
