package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.PostFeed
import xyz.robinjoon.notionblog.blog.application.model.PostFeedLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostFeedUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException

@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class GetPostFeedService(
    private val scopes: ActivePublicationScopeReader,
    private val posts: PostRepository,
    private val snapshotFailures: SnapshotFailureReporter,
) : GetPostFeedUseCase {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun get(): PostFeedLookupResult {
        val scope = scopes.read() ?: return PostFeedLookupResult.ContentUnavailable
        val excludedPostIds = scope.layoutPostIds + scope.rootPostId

        return try {
            val entries = posts.findRecentPublishedPosts(scope.publicationId, excludedPostIds, 20)
            PostFeedLookupResult.Found(PostFeed(scope.site.metadata, entries))
        } catch (failure: SnapshotContentException) {
            snapshotFailures.report(failure, SnapshotFailureOperation.FEED_LOOKUP, null)
            PostFeedLookupResult.ContentUnavailable
        }
    }
}
