package xyz.robinjoon.notionblog.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.sync.SyncTarget

@Service
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
