package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget

@Transactional
class CancelInactivePostSynchronizationService(
    private val publicationRepository: PublicationRepository,
    private val syncStateRepository: SyncStateRepository,
) {
    @Transactional
    fun cancelIfInactive(postId: PostId) {
        val publication = publicationRepository.findCurrent() ?: return
        if (publication.activeRevisionId == null) return
        if (postId in publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId))) return

        syncStateRepository.delete(SyncTarget.Post(postId))
    }
}
