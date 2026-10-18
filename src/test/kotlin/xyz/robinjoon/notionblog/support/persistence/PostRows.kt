package xyz.robinjoon.notionblog.support.persistence

import org.jetbrains.exposed.v1.jdbc.insert
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.CategoryTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.OwnedMediaTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostCategoryTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionMediaTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSourceBindingTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SeriesPostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SeriesTable
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import java.time.Instant
import java.time.ZoneOffset

// 행을 직접 넣어, 영속성 테스트가 검사 대상 어댑터만 실행하게 한다.
object PostRows {
    private val at = Instant.parse("2026-10-16T00:00:00Z").atOffset(ZoneOffset.UTC)

    fun post(
        postId: PostId,
        document: SourceDocumentRef,
        firstPublishedAt: Instant? = null,
    ) {
        PostTable.insert {
            it[PostTable.postId] = postId.value
            it[title] = "Title ${postId.value}"
            it[createdAt] = at
            it[updatedAt] = at
            it[PostTable.firstPublishedAt] = firstPublishedAt?.atOffset(ZoneOffset.UTC)
        }
        PostSourceBindingTable.insert {
            it[sourceId] = document.sourceId.value
            it[externalId] = document.externalId
            it[PostSourceBindingTable.postId] = postId.value
        }
    }

    fun revision(
        postId: PostId,
        revisionId: PostRevisionId,
        title: String,
        contentJson: String,
    ) {
        PostRevisionTable.insert {
            it[PostRevisionTable.revisionId] = revisionId.value
            it[PostRevisionTable.postId] = postId.value
            it[PostRevisionTable.title] = title
            it[summary] = null
            it[tagsJson] = "[]"
            it[coverJson] = null
            it[PostRevisionTable.contentJson] = contentJson
            it[sourceRevision] = "source-${revisionId.value}"
            it[capturedAt] = at
        }
    }

    fun publication(
        postId: PostId,
        visibility: PostVisibility,
        latest: PostRevisionId?,
        published: PostRevisionId?,
    ) {
        PostPublicationTable.insert {
            it[PostPublicationTable.postId] = postId.value
            it[PostPublicationTable.visibility] = visibility.name
            it[latestRevisionId] = latest?.value
            it[publishedRevisionId] = published?.value
            it[archived] = false
            it[generation] = 1
            it[changedAt] = at
        }
    }

    fun media(
        key: MediaKey,
        bytes: ByteArray,
    ) {
        OwnedMediaTable.insert {
            it[mediaKey] = key.value
            it[contentType] = "image/png"
            it[byteSize] = bytes.size.toLong()
            it[OwnedMediaTable.bytes] = bytes
            it[storedAt] = at
        }
    }

    fun binding(
        revisionId: PostRevisionId,
        sourceKey: String,
        key: MediaKey?,
    ) {
        PostRevisionMediaTable.insert {
            it[PostRevisionMediaTable.revisionId] = revisionId.value
            it[PostRevisionMediaTable.sourceKey] = sourceKey
            it[sourceUrl] = "$sourceKey?X-Amz-Signature=sig"
            it[expiresAt] = null
            it[mediaKey] = key?.value
        }
    }

    fun category(
        categoryId: CategoryId,
        name: String,
        position: Int,
    ) {
        CategoryTable.insert {
            it[CategoryTable.categoryId] = categoryId.value
            it[CategoryTable.name] = name
            it[normalizedName] = name.lowercase()
            it[CategoryTable.position] = position
        }
    }

    fun assignment(
        postId: PostId,
        categoryId: CategoryId,
    ) {
        PostCategoryTable.insert {
            it[PostCategoryTable.postId] = postId.value
            it[PostCategoryTable.categoryId] = categoryId.value
        }
    }

    fun series(
        seriesId: SeriesId,
        title: String,
    ) {
        SeriesTable.insert {
            it[SeriesTable.seriesId] = seriesId.value
            it[SeriesTable.title] = title
            it[description] = "$title 설명"
        }
    }

    fun seriesMember(
        seriesId: SeriesId,
        postId: PostId,
        position: Int,
    ) {
        SeriesPostTable.insert {
            it[SeriesPostTable.seriesId] = seriesId.value
            it[SeriesPostTable.postId] = postId.value
            it[SeriesPostTable.position] = position
        }
    }
}
