package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionId
import java.time.Instant

interface PublicationRepository {
    fun findCurrent(): BlogPublication?

    fun save(publication: BlogPublication)

    fun findRevision(revisionId: PublicationRevisionId): PublicationRevision?

    fun findActiveRevision(publicationId: PublicationId): PublicationRevision?

    fun findStagingRevisions(publicationId: PublicationId): List<PublicationRevision>

    fun createRevision(
        revision: PublicationRevision,
        transitionedAt: Instant,
    )

    fun updateRevision(
        revision: PublicationRevision,
        transitionedAt: Instant,
    )

    fun saveMembers(
        revisionId: PublicationRevisionId,
        members: Collection<PublicationMember>,
    )

    fun findMembers(revisionId: PublicationRevisionId): List<PublicationMember>

    fun findActiveMemberPostIds(
        publicationId: PublicationId,
        postIds: Set<PostId>,
    ): Set<PostId>

    fun findActiveDirectChildren(
        publicationId: PublicationId,
        parentPostId: PostId,
    ): List<PublicationMember>
}
