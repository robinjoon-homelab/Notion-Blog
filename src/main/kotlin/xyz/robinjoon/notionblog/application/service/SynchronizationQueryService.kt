package xyz.robinjoon.notionblog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.application.model.PublicationSynchronizationContext
import xyz.robinjoon.notionblog.application.port.input.SynchronizationQueryUseCase
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Instant

@Transactional(readOnly = true)
class SynchronizationQueryService(
    private val siteConfigurationRepository: SiteConfigurationRepository,
    private val publicationRepository: PublicationRepository,
    private val postRepository: PostRepository,
    private val syncStateRepository: SyncStateRepository,
) : SynchronizationQueryUseCase {
    @Transactional(readOnly = true)
    fun loadPublication(): PublicationSynchronizationContext? {
        val siteConfiguration = siteConfigurationRepository.findCurrent() ?: return null
        val publication = publicationRepository.findCurrent() ?: return null
        require(siteConfiguration.publicationId == publication.id) {
            "current site configuration and publication must reference the same publication"
        }

        return PublicationSynchronizationContext(
            publicationId = publication.id,
            rootDocument = siteConfiguration.rootDocument,
        )
    }

    @Transactional(readOnly = true)
    fun loadPost(postId: PostId): PostSynchronizationContext? {
        val publication = publicationRepository.findCurrent() ?: return null
        if (publication.activeRevisionId == null) {
            return null
        }
        if (postId !in publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId))) {
            return null
        }

        val sourceBinding = postRepository.findBinding(postId) ?: return null
        val children = publicationRepository.findActiveDirectChildren(publication.id, postId)
        val childPostIds = children.mapTo(linkedSetOf()) { it.postId }
        val childBindings = postRepository.findBindingsByPostIds(childPostIds)
        val missingChildPostIds = childPostIds - childBindings.keys
        check(missingChildPostIds.isEmpty()) {
            "active publication child posts must have source bindings: $missingChildPostIds"
        }

        return PostSynchronizationContext(
            publicationId = publication.id,
            postId = postId,
            sourceDocument = sourceBinding.sourceDocument,
            activeDirectChildren = childBindings.values.mapTo(linkedSetOf()) { it.sourceDocument },
        )
    }

    @Transactional(readOnly = true)
    override fun findDueTargets(
        now: Instant,
        limit: Int,
    ): List<SyncTarget> {
        require(limit > 0) { "due target limit must be positive" }
        return syncStateRepository.findDue(now, limit).map { it.target }
    }
}
