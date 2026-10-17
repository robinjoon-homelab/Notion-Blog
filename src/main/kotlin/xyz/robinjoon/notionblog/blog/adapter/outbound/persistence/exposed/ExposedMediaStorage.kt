package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.OwnedMediaTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionMediaTable
import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.application.model.MediaContent
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import java.time.Instant

// 파일 바이트를 SHA-256 키로 저장한다. 저장된 키는 다시 쓰지 않는다. 같은 바이트가 다른 콘텐츠 타입으로 올 수 있는데, 저장된
// 행을 바꾸면 이미 게시된 파일이 제공되는 형식이 달라지기 때문이다.
class ExposedMediaStorage : MediaStoragePort {
    override fun save(
        media: Collection<FetchedMedia>,
        storedAt: Instant,
    ) {
        media.distinctBy { it.mediaKey }.forEach { file ->
            OwnedMediaTable.insertIgnore {
                it[mediaKey] = file.mediaKey.value
                it[contentType] = file.contentType
                it[byteSize] = file.size.toLong()
                it[bytes] = file.bytes
                it[OwnedMediaTable.storedAt] = storedAt.asOffsetDateTime()
            }
        }
    }

    override fun findPublic(key: MediaKey): MediaContent? {
        val published =
            PostRevisionMediaTable
                .join(
                    PostPublicationTable,
                    JoinType.INNER,
                    additionalConstraint = { PostRevisionMediaTable.revisionId eq PostPublicationTable.publishedRevisionId },
                ).select(PostRevisionMediaTable.mediaKey)
                .where { bound(key) and (PostPublicationTable.visibility eq PostVisibility.PUBLIC.name) }
                .limit(1)
                .any()
        return if (published) load(key) else null
    }

    override fun findForPreview(key: MediaKey): MediaContent? {
        val bound =
            PostRevisionMediaTable
                .select(PostRevisionMediaTable.mediaKey)
                .where { bound(key) }
                .limit(1)
                .any()
        return if (bound) load(key) else null
    }

    private fun bound(key: MediaKey): Op<Boolean> = PostRevisionMediaTable.mediaKey eq key.value

    private fun load(key: MediaKey): MediaContent? =
        OwnedMediaTable
            .selectAll()
            .where { OwnedMediaTable.mediaKey eq key.value }
            .singleOrNull()
            ?.toContent()

    private fun ResultRow.toContent(): MediaContent =
        MediaContent(MediaKey(this[OwnedMediaTable.mediaKey]), this[OwnedMediaTable.contentType], this[OwnedMediaTable.bytes])
}
