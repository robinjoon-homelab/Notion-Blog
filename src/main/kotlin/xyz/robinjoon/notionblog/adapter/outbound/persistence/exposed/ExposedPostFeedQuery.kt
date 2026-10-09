package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostAvailabilityTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationRevisionTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState

internal fun publishedPostFeedQuery(publicationId: PublicationId): Query =
    activePublicationPosts()
        .innerJoin(PostAvailabilityTable)
        .innerJoin(PostSnapshotTable)
        .select(PostTable.postId, PostTable.title, PostTable.firstPublishedAt, PostSnapshotTable.snapshotJson)
        .where {
            (PublicationTable.publicationId eq publicationId.value) and
                (PublicationRevisionTable.state eq PublicationRevisionState.ACTIVE.name) and
                (PostAvailabilityTable.status eq PostAvailabilityStatus.PUBLISHED.name) and
                PostTable.firstPublishedAt.isNotNull()
        }

private fun activePublicationPosts(): Join =
    Join(
        table = PublicationTable,
        otherTable = PublicationRevisionTable,
        joinType = JoinType.INNER,
        additionalConstraint = {
            (PublicationTable.activeRevisionId eq PublicationRevisionTable.revisionId) and
                (PublicationTable.publicationId eq PublicationRevisionTable.publicationId)
        },
    ).join(
        PublicationMemberTable,
        JoinType.INNER,
        PublicationRevisionTable.revisionId,
        PublicationMemberTable.revisionId,
    ).join(
        PostTable,
        JoinType.INNER,
        PublicationMemberTable.postId,
        PostTable.postId,
    )
