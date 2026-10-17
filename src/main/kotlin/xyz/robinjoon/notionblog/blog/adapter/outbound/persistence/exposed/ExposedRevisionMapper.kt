package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId

// 리비전 행을 매핑한다. 저장된 JSON은 정규화하지 않고 복원하므로 읽기가 검토된 내용을 다시 쓰지 않는다.
class ExposedRevisionMapper(
    private val snapshotCodec: JsonBlockTreeSnapshotCodec,
    private val propertiesCodec: JsonPostPropertiesCodec,
) {
    fun write(
        statement: UpdateBuilder<*>,
        revision: PostRevision,
    ) {
        statement[PostRevisionTable.revisionId] = revision.id.value
        statement[PostRevisionTable.postId] = revision.postId.value
        statement[PostRevisionTable.title] = revision.properties.title
        statement[PostRevisionTable.summary] = revision.properties.summary
        statement[PostRevisionTable.tagsJson] = propertiesCodec.encodeTags(revision.properties.tags)
        statement[PostRevisionTable.coverJson] = propertiesCodec.encodeCover(revision.properties.cover)
        statement[PostRevisionTable.contentJson] = snapshotCodec.encode(revision.content)
        statement[PostRevisionTable.sourceRevision] = revision.sourceRevision.value
        statement[PostRevisionTable.capturedAt] = revision.capturedAt.asOffsetDateTime()
    }

    // 레거시 스냅숏에는 제목만 있었고, 그 JSON은 바이트 단위로 그대로 복사한다.
    fun writeLegacy(
        statement: UpdateBuilder<*>,
        revisionId: PostRevisionId,
        snapshot: ResultRow,
    ) {
        statement[PostRevisionTable.revisionId] = revisionId.value
        statement[PostRevisionTable.postId] = snapshot[PostSnapshotTable.postId]
        statement[PostRevisionTable.title] = snapshot[PostTable.title]
        statement[PostRevisionTable.summary] = null
        statement[PostRevisionTable.tagsJson] = propertiesCodec.encodeTags(emptyList())
        statement[PostRevisionTable.coverJson] = null
        statement[PostRevisionTable.contentJson] = snapshot[PostSnapshotTable.snapshotJson]
        statement[PostRevisionTable.sourceRevision] = snapshot[PostSnapshotTable.sourceRevision]
        statement[PostRevisionTable.capturedAt] = snapshot[PostSnapshotTable.capturedAt]
    }

    fun read(row: ResultRow): PostRevision {
        val revisionId = row[PostRevisionTable.revisionId]
        return try {
            PostRevision(
                id = PostRevisionId(revisionId),
                postId = PostId(row[PostRevisionTable.postId]),
                properties = properties(row),
                content = snapshotCodec.decode(row[PostRevisionTable.contentJson]),
                sourceRevision = SourceRevision(row[PostRevisionTable.sourceRevision]),
                capturedAt = row[PostRevisionTable.capturedAt].toInstant(),
            )
        } catch (exception: IllegalArgumentException) {
            throw SnapshotContentException("unable to decode revision $revisionId", exception)
        }
    }

    private fun properties(row: ResultRow): PostProperties =
        PostProperties(
            title = row[PostRevisionTable.title],
            summary = row[PostRevisionTable.summary],
            tags = propertiesCodec.decodeTags(row[PostRevisionTable.tagsJson]),
            cover = propertiesCodec.decodeCover(row[PostRevisionTable.coverJson]),
        )
}
