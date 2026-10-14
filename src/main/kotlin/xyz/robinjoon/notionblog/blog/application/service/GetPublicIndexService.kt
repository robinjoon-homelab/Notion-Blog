package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicIndex
import xyz.robinjoon.notionblog.blog.application.model.PublicIndexEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicIndexLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicIndexUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException

@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class GetPublicIndexService(
    private val scopes: ActivePublicationScopeReader,
    private val posts: PostRepository,
    private val snapshotFailures: SnapshotFailureReporter,
) : GetPublicIndexUseCase {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun get(): PublicIndexLookupResult {
        val scope = scopes.read() ?: return PublicIndexLookupResult.ContentUnavailable

        return try {
            // The root stays listed even when a layout slot points at the root document.
            val published = posts.findPublishedPosts(scope.publicationId, scope.layoutPostIds - scope.rootPostId)
            val (root, others) = published.partition { it.post.id == scope.rootPostId }
            val entries = root.map { it.toIndexEntry(root = true) } + others.map { it.toIndexEntry(root = false) }
            PublicIndexLookupResult.Found(PublicIndex(scope.site.metadata, entries))
        } catch (failure: SnapshotContentException) {
            snapshotFailures.report(failure, SnapshotFailureOperation.INDEX_LOOKUP, null)
            PublicIndexLookupResult.ContentUnavailable
        }
    }

    private fun PostFeedEntry.toIndexEntry(root: Boolean): PublicIndexEntry = PublicIndexEntry(post, root, firstPublishedAt, lastModifiedAt)
}
