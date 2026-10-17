package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionMediaTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSourceBindingTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.application.model.PostRevisionSummary
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.DuplicateSourceBindingException
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostPublication
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import java.net.URI
import java.time.Instant

class ExposedPostRepository(
    private val revisions: ExposedRevisionMapper,
) : PostRepository {
    override fun find(postId: PostId): Post? =
        managedPosts()
            .where { PostPublicationTable.postId eq postId.value }
            .singleOrNull()
            ?.toPost()

    // 게시 상태 행만 잠근다. 조인에 FOR UPDATE를 거는 방식은 이식성이 없다.
    override fun lock(postId: PostId): Post? {
        PostPublicationTable
            .select(PostPublicationTable.postId)
            .where { PostPublicationTable.postId eq postId.value }
            .forUpdate()
            .singleOrNull()
            ?: return null
        return find(postId)
    }

    override fun findAllManaged(): List<Post> = managedPosts().map { it.toPost() }

    override fun findTitles(postIds: Set<PostId>): Map<PostId, String> {
        if (postIds.isEmpty()) return emptyMap()

        return PostTable
            .select(PostTable.postId, PostTable.title)
            .where { PostTable.postId inList postIds.map(PostId::value) }
            .associate { row -> PostId(row[PostTable.postId]) to row[PostTable.title] }
    }

    override fun findBinding(sourceDocument: SourceDocumentRef): PostSourceBinding? =
        PostSourceBindingTable
            .selectAll()
            .where { sourceDocument.matches() }
            .singleOrNull()
            ?.toBinding()

    override fun findBindingsBySourceDocuments(sourceDocuments: Set<SourceDocumentRef>): Map<SourceDocumentRef, PostSourceBinding> {
        if (sourceDocuments.isEmpty()) return emptyMap()

        return PostSourceBindingTable
            .selectAll()
            .where { sourceDocuments.map { it.matches() }.reduce { combined, condition -> combined or condition } }
            .map { it.toBinding() }
            .associateBy(PostSourceBinding::sourceDocument)
    }

    override fun register(
        post: Post,
        title: String,
        registeredAt: Instant,
    ) {
        ensureBindable(post.id, post.source)
        if (!postRowExists(post.id)) {
            insertPostRow(post, title, registeredAt)
        } else {
            recordFirstPublication(post)
        }
        insertBinding(post)
        if (find(post.id) == null) {
            PostPublicationTable.insert {
                it[postId] = post.id.value
                writePublication(it, post, registeredAt)
            }
        }
    }

    override fun save(
        post: Post,
        changedAt: Instant,
    ) {
        val updated =
            PostPublicationTable.update({ PostPublicationTable.postId eq post.id.value }) {
                writePublication(it, post, changedAt)
            }
        check(updated == 1) { "post ${post.id.value} must be registered before it is saved" }
        recordFirstPublication(post)
    }

    override fun saveRevision(
        revision: PostRevision,
        media: Map<SourceMediaKey, MediaKey>,
    ) {
        val files = revision.sourceHostedMedia().associateBy { SourceMediaKey.of(it.url) }
        require(files.keys.all(media::containsKey)) { "every source-hosted file of a new revision must have a stored copy" }
        PostRevisionTable.insert { revisions.write(it, revision) }
        insertMediaRows(revision.id, files) { key -> media.getValue(key) }
        PostTable.update({ PostTable.postId eq revision.postId.value }) {
            it[title] = revision.title
            it[updatedAt] = revision.capturedAt.asOffsetDateTime()
        }
    }

    override fun copyLegacySnapshot(
        postId: PostId,
        revisionId: PostRevisionId,
        media: Set<MediaSource.SourceHosted>,
    ) {
        val row =
            PostSnapshotTable
                .join(PostTable, JoinType.INNER, PostSnapshotTable.postId, PostTable.postId)
                .selectAll()
                .where { PostSnapshotTable.postId eq postId.value }
                .single()
        PostRevisionTable.insert { revisions.writeLegacy(it, revisionId, row) }
        insertMediaRows(revisionId, media.associateBy { SourceMediaKey.of(it.url) }) { null }
    }

    override fun findRevision(
        postId: PostId,
        revisionId: PostRevisionId,
    ): PostRevision? =
        PostRevisionTable
            .selectAll()
            .where { (PostRevisionTable.postId eq postId.value) and (PostRevisionTable.revisionId eq revisionId.value) }
            .singleOrNull()
            ?.let(revisions::read)

    override fun findRevisionSummaries(revisionIds: Set<PostRevisionId>): Map<PostRevisionId, PostRevisionSummary> {
        if (revisionIds.isEmpty()) return emptyMap()

        return PostRevisionTable
            .select(PostRevisionTable.revisionId, PostRevisionTable.title, PostRevisionTable.sourceRevision, PostRevisionTable.capturedAt)
            .where { PostRevisionTable.revisionId inList revisionIds.map(PostRevisionId::value) }
            .map { row ->
                PostRevisionSummary(
                    id = PostRevisionId(row[PostRevisionTable.revisionId]),
                    title = row[PostRevisionTable.title],
                    sourceRevision = SourceRevision(row[PostRevisionTable.sourceRevision]),
                    capturedAt = row[PostRevisionTable.capturedAt].toInstant(),
                )
            }.associateBy(PostRevisionSummary::id)
    }

    override fun findMediaBindings(revisionIds: Set<PostRevisionId>): Map<PostRevisionId, Map<SourceMediaKey, MediaKey>> {
        if (revisionIds.isEmpty()) return emptyMap()

        return PostRevisionMediaTable
            .select(PostRevisionMediaTable.revisionId, PostRevisionMediaTable.sourceKey, PostRevisionMediaTable.mediaKey)
            .where { revisionIds.contain() and PostRevisionMediaTable.mediaKey.isNotNull() }
            .groupBy({ PostRevisionId(it[PostRevisionMediaTable.revisionId]) }) { row -> row.toMediaBinding() }
            .mapValues { (_, bindings) -> bindings.toMap() }
    }

    override fun findUnboundMedia(revisionIds: Set<PostRevisionId>): Map<PostRevisionId, Set<MediaSource.SourceHosted>> {
        if (revisionIds.isEmpty()) return emptyMap()

        return PostRevisionMediaTable
            .selectAll()
            .where { revisionIds.contain() and PostRevisionMediaTable.mediaKey.isNull() }
            .groupBy({ PostRevisionId(it[PostRevisionMediaTable.revisionId]) }) { row ->
                MediaSource.SourceHosted(URI(row[PostRevisionMediaTable.sourceUrl]), row[PostRevisionMediaTable.expiresAt]?.toInstant())
            }.mapValues { (_, media) -> media.toSet() }
    }

    override fun bindMissingMedia(
        revisionId: PostRevisionId,
        media: Map<SourceMediaKey, MediaKey>,
    ) {
        media.forEach { (sourceKey, mediaKey) ->
            PostRevisionMediaTable.update({
                (PostRevisionMediaTable.revisionId eq revisionId.value) and
                    (PostRevisionMediaTable.sourceKey eq sourceKey.value) and
                    PostRevisionMediaTable.mediaKey.isNull()
            }) { it[PostRevisionMediaTable.mediaKey] = mediaKey.value }
        }
    }

    private fun managedPosts(): Query =
        PostPublicationTable
            .join(PostTable, JoinType.INNER, PostPublicationTable.postId, PostTable.postId)
            .join(PostSourceBindingTable, JoinType.INNER, PostTable.postId, PostSourceBindingTable.postId)
            .selectAll()

    private fun ResultRow.toPost(): Post =
        Post.restore(
            id = PostId(this[PostPublicationTable.postId]),
            source = toBinding().sourceDocument,
            latestRevisionId = this[PostPublicationTable.latestRevisionId]?.let(::PostRevisionId),
            publication =
                PostPublication(
                    visibility = PostVisibility.valueOf(this[PostPublicationTable.visibility]),
                    publishedRevisionId = this[PostPublicationTable.publishedRevisionId]?.let(::PostRevisionId),
                    archived = this[PostPublicationTable.archived],
                    generation = this[PostPublicationTable.generation],
                    firstPublishedAt = this[PostTable.firstPublishedAt]?.toInstant(),
                ),
        )

    private fun writePublication(
        statement: UpdateBuilder<*>,
        post: Post,
        changedAt: Instant,
    ) {
        statement[PostPublicationTable.visibility] = post.publication.visibility.name
        statement[PostPublicationTable.latestRevisionId] = post.latestRevisionId?.value
        statement[PostPublicationTable.publishedRevisionId] = post.publication.publishedRevisionId?.value
        statement[PostPublicationTable.archived] = post.publication.archived
        statement[PostPublicationTable.generation] = post.publication.generation
        statement[PostPublicationTable.changedAt] = changedAt.asOffsetDateTime()
    }

    // 최초 게시 시각은 한 번만 기록하며 이미 있는 값은 바꾸지 않는다.
    private fun recordFirstPublication(post: Post) {
        val firstPublishedAt = post.firstPublishedAt ?: return
        PostTable.update({ (PostTable.postId eq post.id.value) and PostTable.firstPublishedAt.isNull() }) {
            it[PostTable.firstPublishedAt] = firstPublishedAt.asOffsetDateTime()
        }
    }

    private fun postRowExists(postId: PostId): Boolean =
        PostTable
            .select(PostTable.postId)
            .where { PostTable.postId eq postId.value }
            .empty()
            .not()

    private fun insertPostRow(
        post: Post,
        title: String,
        registeredAt: Instant,
    ) {
        PostTable.insert {
            it[postId] = post.id.value
            it[PostTable.title] = title
            it[createdAt] = registeredAt.asOffsetDateTime()
            it[updatedAt] = registeredAt.asOffsetDateTime()
            it[firstPublishedAt] = post.firstPublishedAt?.asOffsetDateTime()
        }
    }

    private fun insertBinding(post: Post) {
        if (findBinding(post.source) != null) return
        try {
            PostSourceBindingTable.insert {
                it[sourceId] = post.source.sourceId.value
                it[externalId] = post.source.externalId
                it[postId] = post.id.value
            }
        } catch (exception: ExposedSQLException) {
            throw DuplicateSourceBindingException("source document was bound concurrently", exception)
        }
    }

    private fun ensureBindable(
        postId: PostId,
        source: SourceDocumentRef,
    ) {
        val sourceBinding = findBinding(source)
        if (sourceBinding != null && sourceBinding.postId != postId) {
            throw DuplicateSourceBindingException("source document is already bound to post ${sourceBinding.postId.value}")
        }
        val postBinding = PostSourceBindingTable.selectAll().where { PostSourceBindingTable.postId eq postId.value }.singleOrNull()
        if (postBinding != null && postBinding.toBinding().sourceDocument != source) {
            throw DuplicateSourceBindingException("post ${postId.value} is already bound to another source document")
        }
    }

    private fun insertMediaRows(
        revisionId: PostRevisionId,
        files: Map<SourceMediaKey, MediaSource.SourceHosted>,
        mediaKeyOf: (SourceMediaKey) -> MediaKey?,
    ) {
        PostRevisionMediaTable.batchInsert(files.entries) { (sourceKey, source) ->
            this[PostRevisionMediaTable.revisionId] = revisionId.value
            this[PostRevisionMediaTable.sourceKey] = sourceKey.value
            this[PostRevisionMediaTable.sourceUrl] = source.url.toString()
            this[PostRevisionMediaTable.expiresAt] = source.expiresAt?.asOffsetDateTime()
            this[PostRevisionMediaTable.mediaKey] = mediaKeyOf(sourceKey)?.value
        }
    }

    private fun ResultRow.toMediaBinding(): Pair<SourceMediaKey, MediaKey> =
        SourceMediaKey(this[PostRevisionMediaTable.sourceKey]) to MediaKey(requireNotNull(this[PostRevisionMediaTable.mediaKey]))

    private fun Set<PostRevisionId>.contain() = PostRevisionMediaTable.revisionId inList map(PostRevisionId::value)

    private fun SourceDocumentRef.matches() =
        (PostSourceBindingTable.sourceId eq sourceId.value) and (PostSourceBindingTable.externalId eq externalId)

    private fun ResultRow.toBinding(): PostSourceBinding =
        PostSourceBinding(
            PostId(this[PostSourceBindingTable.postId]),
            SourceDocumentRef(SourceId(this[PostSourceBindingTable.sourceId]), this[PostSourceBindingTable.externalId]),
        )
}
