package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostAvailabilityTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSourceBindingTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.application.model.LegacyPostRecord
import xyz.robinjoon.notionblog.blog.application.model.LegacySnapshot
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.LegacyPostStore
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.sourceHostedMedia
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionState
import java.util.UUID

class ExposedLegacyPostStore(
    private val snapshotCodec: JsonBlockTreeSnapshotCodec,
) : LegacyPostStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    // 레거시 공개 집합은 관리되지 않는 글이 남아 있을 때만 읽는다. 그래서 모든 글이 관리 대상이 된 뒤에는 모호한 레거시 게시가 이후
    // 시작을 실패시키지 않는다.
    override fun findUnmanaged(): List<LegacyPostRecord> {
        val rows =
            PostSourceBindingTable
                .join(PostTable, JoinType.INNER, PostSourceBindingTable.postId, PostTable.postId)
                .join(PostPublicationTable, JoinType.LEFT, PostTable.postId, PostPublicationTable.postId)
                .join(PostSnapshotTable, JoinType.LEFT, PostTable.postId, PostSnapshotTable.postId)
                .selectAll()
                .where { PostPublicationTable.postId.isNull() }
                .toList()
        if (rows.isEmpty()) return emptyList()

        val legacyPublic = legacyPublicPostIds()
        return rows.map { row -> row.toRecord(legacyPublic) }
    }

    // 레거시 읽기 경로가 제공하던 글과 정확히 같다. 하나뿐인 게시의 활성 리비전에 속하고 availability가 PUBLISHED이며
    // 저장된 스냅숏이 있는 글이다. 해석할 수 없는 스냅숏도 포함하며, 그 글은 이전처럼 503을 계속 응답한다.
    private fun legacyPublicPostIds(): Set<PostId> {
        val publicationId = legacyPublicationId() ?: return emptySet()
        return PublicationTable
            .join(
                PublicationRevisionTable,
                JoinType.INNER,
                additionalConstraint = {
                    (PublicationTable.activeRevisionId eq PublicationRevisionTable.revisionId) and
                        (PublicationTable.publicationId eq PublicationRevisionTable.publicationId)
                },
            ).join(PublicationMemberTable, JoinType.INNER, PublicationRevisionTable.revisionId, PublicationMemberTable.revisionId)
            .join(PostAvailabilityTable, JoinType.INNER, PublicationMemberTable.postId, PostAvailabilityTable.postId)
            .join(PostSnapshotTable, JoinType.INNER, PublicationMemberTable.postId, PostSnapshotTable.postId)
            .select(PublicationMemberTable.postId)
            .where {
                (PublicationTable.publicationId eq publicationId) and
                    (PublicationRevisionTable.state eq PublicationRevisionState.ACTIVE.name) and
                    (PostAvailabilityTable.status eq LEGACY_PUBLISHED)
            }.mapTo(linkedSetOf()) { PostId(it[PublicationMemberTable.postId]) }
    }

    // 레거시 읽기 경로는 게시가 둘 이상이면 아무것도 제공하지 않았으므로, 이전 작업도 추측하지 않고 실패한다. 사이트 설정은 외래 키로
    // 게시를 참조하므로 하나뿐인 게시가 곧 사이트가 쓰는 게시다.
    private fun legacyPublicationId(): UUID? {
        val publicationIds =
            PublicationTable
                .select(PublicationTable.publicationId)
                .limit(2)
                .map { it[PublicationTable.publicationId] }
        check(publicationIds.size <= 1) { "more than one legacy publication exists, so the legacy public set is ambiguous" }
        return publicationIds.singleOrNull()
    }

    private fun ResultRow.toRecord(legacyPublic: Set<PostId>): LegacyPostRecord {
        val postId = PostId(this[PostTable.postId])
        val snapshot = toSnapshot(postId)
        return LegacyPostRecord(
            binding =
                PostSourceBinding(
                    postId,
                    SourceDocumentRef(SourceId(this[PostSourceBindingTable.sourceId]), this[PostSourceBindingTable.externalId]),
                ),
            title = this[PostTable.title],
            snapshot = snapshot,
            legacyPublic = snapshot != null && postId in legacyPublic,
            firstPublishedAt = this[PostTable.firstPublishedAt]?.toInstant(),
        )
    }

    private fun ResultRow.toSnapshot(postId: PostId): LegacySnapshot? {
        val capturedAt = getOrNull(PostSnapshotTable.capturedAt) ?: return null
        return LegacySnapshot(capturedAt.toInstant(), media(postId, this[PostSnapshotTable.snapshotJson]))
    }

    private fun media(
        postId: PostId,
        snapshotJson: String,
    ): Set<MediaSource.SourceHosted> =
        try {
            snapshotCodec.decode(snapshotJson).sourceHostedMedia()
        } catch (exception: IllegalArgumentException) {
            logger.warn("Legacy snapshot of post {} is not decodable ({})", postId.value, exception.javaClass.simpleName)
            emptySet()
        }

    private companion object {
        // 제거된 게시 트리 동기화가 기록하던 레거시 availability 상태다.
        const val LEGACY_PUBLISHED = "PUBLISHED"
    }
}
