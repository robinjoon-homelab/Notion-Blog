package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostAvailabilityTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostSourceBindingTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.application.model.StoredPost
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.time.Instant
import java.time.ZoneOffset

class ExposedPostRepository(
    private val snapshotCodec: JsonBlockTreeSnapshotCodec,
) : PostRepository {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun find(postId: PostId): StoredPost? {
        val row =
            PostTable
                .innerJoin(PostSnapshotTable)
                .selectAll()
                .where { PostTable.postId eq postId.value }
                .singleOrNull()
                ?: return null

        return StoredPost(
            row.toPost(),
            SourceRevision(row[PostSnapshotTable.sourceRevision]),
            row[PostSnapshotTable.capturedAt].toInstant(),
        )
    }

    override fun findRecentPublishedPosts(
        publicationId: PublicationId,
        excludedPostIds: Set<PostId>,
        limit: Int,
    ): List<PostFeedEntry> {
        val query = publishedPostFeedQuery(publicationId)
        if (excludedPostIds.isNotEmpty()) {
            query.andWhere { PostTable.postId notInList excludedPostIds.map(PostId::value) }
        }
        return query
            .orderBy(PostTable.firstPublishedAt to SortOrder.DESC, PostTable.postId to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                PostFeedEntry(row.toPost(), requireNotNull(row[PostTable.firstPublishedAt]).toInstant())
            }
    }

    override fun recordFirstPublication(
        postId: PostId,
        observedAt: Instant,
    ) {
        PostTable.update({ (PostTable.postId eq postId.value) and PostTable.firstPublishedAt.isNull() }) {
            it[firstPublishedAt] = observedAt.asOffsetDateTime()
        }
    }

    override fun findBinding(postId: PostId): PostSourceBinding? =
        PostSourceBindingTable
            .selectAll()
            .where { PostSourceBindingTable.postId eq postId.value }
            .singleOrNull()
            ?.toBinding()

    override fun findBinding(sourceDocument: SourceDocumentRef): PostSourceBinding? =
        PostSourceBindingTable
            .selectAll()
            .where {
                (PostSourceBindingTable.sourceId eq sourceDocument.sourceId.value) and
                    (PostSourceBindingTable.externalId eq sourceDocument.externalId)
            }.singleOrNull()
            ?.toBinding()

    override fun findBindingsBySourceDocuments(sourceDocuments: Set<SourceDocumentRef>): Map<SourceDocumentRef, PostSourceBinding> {
        if (sourceDocuments.isEmpty()) return emptyMap()

        val conditions =
            sourceDocuments.map { sourceDocument ->
                (PostSourceBindingTable.sourceId eq sourceDocument.sourceId.value) and
                    (PostSourceBindingTable.externalId eq sourceDocument.externalId)
            }
        return PostSourceBindingTable
            .selectAll()
            .where { conditions.reduce { combined, condition -> combined or condition } }
            .associate { row ->
                val binding = row.toBinding()
                binding.sourceDocument to binding
            }
    }

    override fun findBindingsByPostIds(postIds: Set<PostId>): Map<PostId, PostSourceBinding> {
        if (postIds.isEmpty()) return emptyMap()

        return PostSourceBindingTable
            .selectAll()
            .where { PostSourceBindingTable.postId inList postIds.map { it.value } }
            .associate { row ->
                val binding = row.toBinding()
                binding.postId to binding
            }
    }

    override fun saveIdentity(
        binding: PostSourceBinding,
        title: String,
        changedAt: Instant,
    ) {
        ensureBindingCanBeSaved(binding)

        val updated =
            PostTable.update({ PostTable.postId eq binding.postId.value }) {
                it[PostTable.title] = title
                it[PostTable.updatedAt] = changedAt.asOffsetDateTime()
            }
        if (updated == 0) {
            PostTable.insert {
                it[postId] = binding.postId.value
                it[PostTable.title] = title
                it[createdAt] = changedAt.asOffsetDateTime()
                it[updatedAt] = changedAt.asOffsetDateTime()
            }
        }

        if (findBinding(binding.postId) == null) {
            PostSourceBindingTable.insert {
                it[sourceId] = binding.sourceDocument.sourceId.value
                it[externalId] = binding.sourceDocument.externalId
                it[postId] = binding.postId.value
            }
        }
    }

    override fun saveSnapshot(
        post: Post,
        sourceRevision: SourceRevision,
        capturedAt: Instant,
    ) {
        PostSnapshotTable.upsert(PostSnapshotTable.postId) {
            it[postId] = post.id.value
            it[snapshotJson] = snapshotCodec.encode(post.content)
            it[PostSnapshotTable.sourceRevision] = sourceRevision.value
            it[PostSnapshotTable.capturedAt] = capturedAt.asOffsetDateTime()
        }
        PostTable.update({ PostTable.postId eq post.id.value }) {
            it[title] = post.title
            it[updatedAt] = capturedAt.asOffsetDateTime()
        }
    }

    override fun findAvailability(postId: PostId): PostAvailability? =
        PostAvailabilityTable
            .selectAll()
            .where { PostAvailabilityTable.postId eq postId.value }
            .singleOrNull()
            ?.toAvailability()

    override fun findAvailabilities(postIds: Set<PostId>): Map<PostId, PostAvailability> {
        if (postIds.isEmpty()) return emptyMap()

        return PostAvailabilityTable
            .selectAll()
            .where { PostAvailabilityTable.postId inList postIds.map { it.value } }
            .associate { row ->
                val availability = row.toAvailability()
                availability.postId to availability
            }
    }

    override fun saveAvailability(availability: PostAvailability) {
        PostAvailabilityTable.upsert(PostAvailabilityTable.postId) {
            it[postId] = availability.postId.value
            it[status] = availability.status.name
            it[confirmedAt] = availability.confirmedAt.asOffsetDateTime()
        }
    }

    override fun saveAvailabilities(availabilities: Collection<PostAvailability>) {
        if (availabilities.isEmpty()) return

        PostAvailabilityTable.batchUpsert(availabilities, PostAvailabilityTable.postId) { availability ->
            this[PostAvailabilityTable.postId] = availability.postId.value
            this[PostAvailabilityTable.status] = availability.status.name
            this[PostAvailabilityTable.confirmedAt] = availability.confirmedAt.asOffsetDateTime()
        }
    }

    override fun findRenderablePostIds(postIds: Set<PostId>): Set<PostId> {
        if (postIds.isEmpty()) return emptySet()

        return PostSnapshotTable
            .selectAll()
            .where { PostSnapshotTable.postId inList postIds.map { it.value } }
            .mapNotNullTo(linkedSetOf()) { row ->
                try {
                    snapshotCodec.decode(row[PostSnapshotTable.snapshotJson])
                    PostId(row[PostSnapshotTable.postId])
                } catch (exception: IllegalArgumentException) {
                    logger.warn(
                        "Snapshot for post {} is not renderable ({})",
                        row[PostSnapshotTable.postId],
                        exception.javaClass.simpleName,
                    )
                    null
                }
            }
    }

    private fun ResultRow.toPost(): Post {
        val postId = PostId(this[PostTable.postId])
        val content =
            try {
                snapshotCodec.decode(this[PostSnapshotTable.snapshotJson])
            } catch (exception: IllegalArgumentException) {
                throw SnapshotContentException("unable to decode snapshot for post ${postId.value}", exception)
            }
        return Post(postId, this[PostTable.title], content)
    }

    private fun ensureBindingCanBeSaved(binding: PostSourceBinding) {
        val sourceBinding = findBinding(binding.sourceDocument)
        check(sourceBinding == null || sourceBinding.postId == binding.postId) {
            "source document is already bound to post ${sourceBinding?.postId?.value}"
        }
        val postBinding = findBinding(binding.postId)
        check(postBinding == null || postBinding.sourceDocument == binding.sourceDocument) {
            "post ${binding.postId.value} is already bound to another source document"
        }
    }

    private fun ResultRow.toBinding(): PostSourceBinding =
        PostSourceBinding(
            PostId(this[PostSourceBindingTable.postId]),
            SourceDocumentRef(SourceId(this[PostSourceBindingTable.sourceId]), this[PostSourceBindingTable.externalId]),
        )

    private fun ResultRow.toAvailability(): PostAvailability =
        PostAvailability(
            PostId(this[PostAvailabilityTable.postId]),
            PostAvailabilityStatus.valueOf(this[PostAvailabilityTable.status]),
            this[PostAvailabilityTable.confirmedAt].toInstant(),
        )

    private fun Instant.asOffsetDateTime() = atOffset(ZoneOffset.UTC)
}
