package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.PostLookupResult
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.post.PostAvailabilityStatus
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.BlogPublication

class GetPublishedPostService(
    private val publicationRepository: PublicationRepository,
    private val postRepository: PostRepository,
    private val snapshotFailures: SnapshotFailureReporter,
) {
    fun getRoot(): PostLookupResult {
        val publication = publicationRepository.findCurrent() ?: return PostLookupResult.ContentUnavailable
        val rootPostId = publication.rootPostId ?: return PostLookupResult.ContentUnavailable

        return get(publication, rootPostId)
    }

    fun get(postId: PostId): PostLookupResult {
        val publication = publicationRepository.findCurrent() ?: return PostLookupResult.ContentUnavailable

        return get(publication, postId)
    }

    private fun get(
        publication: BlogPublication,
        postId: PostId,
    ): PostLookupResult {
        if (publication.activeRevisionId == null) {
            return PostLookupResult.ContentUnavailable
        }

        val activeMembers = publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId))
        if (postId !in activeMembers) {
            return PostLookupResult.NotFound
        }

        when (postRepository.findAvailability(postId)?.status) {
            null -> return PostLookupResult.ContentUnavailable
            PostAvailabilityStatus.UNPUBLISHED -> return PostLookupResult.NotFound
            PostAvailabilityStatus.PUBLISHED -> Unit
        }

        return try {
            postRepository.find(postId)?.let {
                PostLookupResult.Found(it.post, it.firstPublishedAt, it.capturedAt, postId == publication.rootPostId)
            } ?: PostLookupResult.ContentUnavailable
        } catch (failure: SnapshotContentException) {
            snapshotFailures.report(failure, SnapshotFailureOperation.POST_LOOKUP, postId)
            PostLookupResult.ContentUnavailable
        }
    }
}
