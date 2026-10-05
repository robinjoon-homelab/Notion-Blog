package xyz.robinjoon.notionblog.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.PostFeed
import xyz.robinjoon.notionblog.application.model.PostFeedLookupResult
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState

@Service
class GetPostFeedService(
    private val posts: PostRepository,
    private val publications: PublicationRepository,
    private val siteConfigurations: SiteConfigurationRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(): PostFeedLookupResult {
        val site = siteConfigurations.findCurrent() ?: return PostFeedLookupResult.ContentUnavailable
        val publication = publications.findCurrent() ?: return PostFeedLookupResult.ContentUnavailable
        if (site.publicationId != publication.id) {
            return PostFeedLookupResult.ContentUnavailable
        }
        val rootPostId = publication.rootPostId ?: return PostFeedLookupResult.ContentUnavailable
        val activeRevisionId = publication.activeRevisionId ?: return PostFeedLookupResult.ContentUnavailable
        val revision = publications.findActiveRevision(publication.id) ?: return PostFeedLookupResult.ContentUnavailable
        if (revision.id != activeRevisionId || revision.publicationId != publication.id || revision.state != PublicationRevisionState.ACTIVE) {
            return PostFeedLookupResult.ContentUnavailable
        }

        val layoutReferences = setOfNotNull(site.headerDocument, site.footerDocument)
        val layoutBindings = posts.findBindingsBySourceDocuments(layoutReferences)
        val excludedPostIds = layoutBindings.values.mapTo(mutableSetOf(rootPostId)) { it.postId }

        return try {
            val entries = posts.findRecentPublishedPosts(publication.id, excludedPostIds, 20)
            PostFeedLookupResult.Found(PostFeed(site.metadata, entries))
        } catch (_: SnapshotContentException) {
            PostFeedLookupResult.ContentUnavailable
        }
    }
}
