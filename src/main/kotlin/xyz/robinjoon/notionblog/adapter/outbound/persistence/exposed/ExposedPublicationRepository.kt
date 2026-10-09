package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationRevisionTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import java.time.Instant
import java.time.ZoneOffset

class ExposedPublicationRepository : PublicationRepository {
    override fun findCurrent(): BlogPublication? {
        val publications = PublicationTable.selectAll().limit(2).map(::toPublication)
        return when (publications.size) {
            0 -> null
            1 -> publications.single()
            else -> error("more than one publication exists")
        }
    }

    override fun save(publication: BlogPublication) {
        PublicationTable.upsert(PublicationTable.publicationId) {
            it[publicationId] = publication.id.value
            it[rootPostId] = publication.rootPostId?.value
            it[activeRevisionId] = publication.activeRevisionId?.value
        }
    }

    override fun findRevision(revisionId: PublicationRevisionId): PublicationRevision? =
        PublicationRevisionTable
            .selectAll()
            .where { PublicationRevisionTable.revisionId eq revisionId.value }
            .singleOrNull()
            ?.let(::toRevision)

    override fun findActiveRevision(publicationId: PublicationId): PublicationRevision? =
        activeRevisions()
            .selectAll()
            .where {
                (PublicationTable.publicationId eq publicationId.value) and
                    (PublicationRevisionTable.state eq PublicationRevisionState.ACTIVE.name)
            }.singleOrNull()
            ?.let(::toRevision)

    override fun findStagingRevisions(publicationId: PublicationId): List<PublicationRevision> =
        PublicationRevisionTable
            .selectAll()
            .where {
                (PublicationRevisionTable.publicationId eq publicationId.value) and
                    (PublicationRevisionTable.state eq PublicationRevisionState.STAGING.name)
            }.orderBy(PublicationRevisionTable.startedAt to SortOrder.ASC)
            .map(::toRevision)

    override fun createRevision(
        revision: PublicationRevision,
        transitionedAt: Instant,
    ) {
        require(revision.state == PublicationRevisionState.STAGING) {
            "new publication revisions must start in staging"
        }
        PublicationRevisionTable.insert {
            it[revisionId] = revision.id.value
            it[publicationId] = revision.publicationId.value
            it[state] = revision.state.name
            it[startedAt] = transitionedAt.atOffset(ZoneOffset.UTC)
            it[activatedAt] = null
        }
    }

    override fun updateRevision(
        revision: PublicationRevision,
        transitionedAt: Instant,
    ) {
        val existing =
            requireNotNull(
                PublicationRevisionTable
                    .selectAll()
                    .where { PublicationRevisionTable.revisionId eq revision.id.value }
                    .singleOrNull(),
            ) { "publication revision must exist before it can be updated" }
        require(existing[PublicationRevisionTable.publicationId] == revision.publicationId.value) {
            "publication revision must remain owned by its publication"
        }

        val updated =
            PublicationRevisionTable.update({ PublicationRevisionTable.revisionId eq revision.id.value }) {
                it[state] = revision.state.name
                it[activatedAt] = revision.activationTimeOnUpdate(existing[PublicationRevisionTable.activatedAt], transitionedAt)
            }
        check(updated == 1) { "publication revision update affected $updated rows" }
    }

    override fun saveMembers(
        revisionId: PublicationRevisionId,
        members: Collection<PublicationMember>,
    ) {
        require(members.all { it.revisionId == revisionId }) {
            "all publication members must belong to the supplied revision"
        }
        if (members.isEmpty()) return

        PublicationMemberTable.batchInsert(members) { member ->
            this[PublicationMemberTable.revisionId] = member.revisionId.value
            this[PublicationMemberTable.postId] = member.postId.value
            this[PublicationMemberTable.parentPostId] = member.parentPostId?.value
            this[PublicationMemberTable.depth] = member.depth
        }
    }

    override fun findMembers(revisionId: PublicationRevisionId): List<PublicationMember> =
        PublicationMemberTable
            .selectAll()
            .where { PublicationMemberTable.revisionId eq revisionId.value }
            .orderBy(PublicationMemberTable.depth to SortOrder.ASC)
            .map(::toMember)

    override fun findActiveMemberPostIds(
        publicationId: PublicationId,
        postIds: Set<PostId>,
    ): Set<PostId> {
        if (postIds.isEmpty()) return emptySet()
        return activeMembers()
            .selectAll()
            .where {
                (PublicationTable.publicationId eq publicationId.value) and
                    (PublicationRevisionTable.state eq PublicationRevisionState.ACTIVE.name) and
                    (PublicationMemberTable.postId inList postIds.map(PostId::value))
            }.mapTo(linkedSetOf()) { PostId(it[PublicationMemberTable.postId]) }
    }

    override fun findActiveDirectChildren(
        publicationId: PublicationId,
        parentPostId: PostId,
    ): List<PublicationMember> =
        activeMembers()
            .selectAll()
            .where {
                (PublicationTable.publicationId eq publicationId.value) and
                    (PublicationRevisionTable.state eq PublicationRevisionState.ACTIVE.name) and
                    (PublicationMemberTable.parentPostId eq parentPostId.value)
            }.orderBy(PublicationMemberTable.depth to SortOrder.ASC)
            .map(::toMember)

    private fun activeRevisions() =
        Join(
            table = PublicationTable,
            otherTable = PublicationRevisionTable,
            joinType = JoinType.INNER,
            additionalConstraint = {
                (PublicationTable.activeRevisionId eq PublicationRevisionTable.revisionId) and
                    (PublicationTable.publicationId eq PublicationRevisionTable.publicationId)
            },
        )

    private fun activeMembers() =
        Join(
            table = PublicationMemberTable,
            otherTable = activeRevisions(),
            joinType = JoinType.INNER,
            additionalConstraint = { PublicationMemberTable.revisionId eq PublicationRevisionTable.revisionId },
        )

    private fun toPublication(row: org.jetbrains.exposed.v1.core.ResultRow): BlogPublication =
        BlogPublication(
            id = PublicationId(row[PublicationTable.publicationId]),
            rootPostId = row[PublicationTable.rootPostId]?.let(::PostId),
            activeRevisionId = row[PublicationTable.activeRevisionId]?.let(::PublicationRevisionId),
        )

    private fun toRevision(row: org.jetbrains.exposed.v1.core.ResultRow): PublicationRevision =
        PublicationRevision(
            id = PublicationRevisionId(row[PublicationRevisionTable.revisionId]),
            publicationId = PublicationId(row[PublicationRevisionTable.publicationId]),
            state = PublicationRevisionState.valueOf(row[PublicationRevisionTable.state]),
        )

    private fun toMember(row: org.jetbrains.exposed.v1.core.ResultRow): PublicationMember =
        PublicationMember(
            revisionId = PublicationRevisionId(row[PublicationMemberTable.revisionId]),
            postId = PostId(row[PublicationMemberTable.postId]),
            parentPostId = row[PublicationMemberTable.parentPostId]?.let(::PostId),
            depth = row[PublicationMemberTable.depth],
        )

    private fun PublicationRevision.activationTimeOnUpdate(
        existingActivationTime: java.time.OffsetDateTime?,
        transitionedAt: Instant,
    ) = when (state) {
        PublicationRevisionState.STAGING -> {
            null
        }

        PublicationRevisionState.ABANDONED -> {
            null
        }

        PublicationRevisionState.ACTIVE -> {
            transitionedAt.atOffset(ZoneOffset.UTC)
        }

        PublicationRevisionState.SUPERSEDED -> {
            requireNotNull(existingActivationTime) {
                "a superseded revision must retain its activation time"
            }
        }
    }
}
