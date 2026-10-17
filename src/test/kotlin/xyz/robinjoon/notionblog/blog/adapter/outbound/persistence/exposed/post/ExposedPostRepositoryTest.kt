package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedRevisionMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.OwnedMediaTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.PostRevisionSummary
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.DuplicateSourceBindingException
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostPublication
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import java.time.Instant
import java.time.ZoneOffset

class ExposedPostRepositoryTest {
    private val database = ExposedH2Database()
    private val snapshotCodec = JsonBlockTreeSnapshotCodec()
    private val repository = ExposedPostRepository(ExposedRevisionMapper(snapshotCodec, JsonPostPropertiesCodec()))
    private val postId = PostFixtures.postId(1)
    private val document = PostFixtures.document("doc-1")
    private val now = Instant.parse("2026-10-16T02:00:00Z")

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `registration creates a private managed post once and keeps it on repeat`() {
        database.inTransaction {
            repository.register(Post.register(postId, document), "Untitled", now)
            repository.register(Post.register(postId, document), "Other title", now)

            assertThat(repository.find(postId)).isEqualTo(Post.register(postId, document))
            assertThat(repository.findAllManaged()).hasSize(1)
            assertThat(repository.findTitles(setOf(postId))).containsEntry(postId, "Untitled")
            assertThat(repository.findBinding(document)?.postId).isEqualTo(postId)
            assertThat(repository.lock(postId)).isEqualTo(Post.register(postId, document))
        }
    }

    @Test
    fun `a source document bound to another post is never rebound`() {
        database.inTransaction {
            repository.register(Post.register(postId, document), "Untitled", now)

            assertThatThrownBy { repository.register(Post.register(PostFixtures.postId(2), document), "Untitled", now) }
                .isInstanceOf(DuplicateSourceBindingException::class.java)
            assertThatThrownBy { repository.register(Post.register(postId, PostFixtures.document("doc-2")), "Untitled", now) }
                .isInstanceOf(DuplicateSourceBindingException::class.java)
            assertThat(repository.findBinding(document)?.postId).isEqualTo(postId)
            assertThat(repository.findBinding(PostFixtures.document("doc-2"))).isNull()
            assertThat(repository.find(PostFixtures.postId(2))).isNull()
            assertThat(repository.find(postId)).isEqualTo(Post.register(postId, document))
            assertThat(repository.findAllManaged()).hasSize(1)
        }
    }

    @Test
    fun `bindings are found for the requested source documents only`() {
        val second = PostFixtures.document("doc-2")
        val unknown = PostFixtures.document("unknown")

        database.inTransaction {
            repository.register(Post.register(postId, document), "First", now)
            repository.register(Post.register(PostFixtures.postId(2), second), "Second", now)
            repository.register(Post.register(PostFixtures.postId(3), PostFixtures.document("doc-3")), "Third", now)

            assertThat(repository.findBindingsBySourceDocuments(setOf(document, second, unknown))).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    document to PostSourceBinding(postId, document),
                    second to PostSourceBinding(PostFixtures.postId(2), second),
                ),
            )
            assertThat(repository.findBindingsBySourceDocuments(emptySet())).isEmpty()
        }
    }

    @Test
    fun `a stored revision round trips properties content and files and renames the post`() {
        val cover = PostFixtures.hosted("cover.png")
        val body = PostFixtures.hosted("body.png")
        val revision =
            PostRevision(
                id = PostFixtures.revisionId(1),
                postId = postId,
                properties = PostProperties("Stored title", "Summary", listOf("kotlin", "notion"), cover),
                content = BlockTree(listOf(PostFixtures.image("image", body))),
                sourceRevision = SourceRevision("rev-1"),
                capturedAt = now,
            )

        database.inTransaction {
            repository.register(Post.register(postId, document), "Untitled", now)
            storeMedia(listOf(key('a'), key('b')))
            repository.saveRevision(revision, mapOf(sourceKey(cover) to key('a'), sourceKey(body) to key('b')))

            assertThat(repository.findRevision(postId, revision.id)).isEqualTo(revision)
            assertThat(repository.findRevisionSummaries(setOf(revision.id)))
                .containsEntry(revision.id, PostRevisionSummary(revision.id, "Stored title", SourceRevision("rev-1"), now))
            assertThat(repository.findMediaBindings(setOf(revision.id)))
                .containsExactlyEntriesOf(mapOf(revision.id to mapOf(sourceKey(cover) to key('a'), sourceKey(body) to key('b'))))
            assertThat(repository.findUnboundMedia(setOf(revision.id))).isEmpty()
            assertThat(repository.findTitles(setOf(postId))).containsEntry(postId, "Stored title")
        }
    }

    @Test
    fun `two revisions keep their own bytes for the same source file name`() {
        val older = PostFixtures.hosted("same.png", signature = "1")
        val newer = PostFixtures.hosted("same.png", signature = "2")
        val first = PostFixtures.revision(postId, PostFixtures.revisionId(1), BlockTree(listOf(PostFixtures.image("i", older))), "rev-1")
        val second = PostFixtures.revision(postId, PostFixtures.revisionId(2), BlockTree(listOf(PostFixtures.image("i", newer))), "rev-2")

        database.inTransaction {
            repository.register(Post.register(postId, document), "Untitled", now)
            storeMedia(listOf(key('a'), key('b')))
            repository.saveRevision(first, mapOf(sourceKey(older) to key('a')))
            repository.saveRevision(second, mapOf(sourceKey(newer) to key('b')))

            assertThat(repository.findMediaBindings(setOf(first.id, second.id))).containsExactlyInAnyOrderEntriesOf(
                mapOf(first.id to mapOf(sourceKey(older) to key('a')), second.id to mapOf(sourceKey(newer) to key('b'))),
            )
        }
    }

    @Test
    fun `a new revision must have a stored copy of every source-hosted file`() {
        val content = BlockTree(listOf(PostFixtures.image("i", PostFixtures.hosted("x.png"))))
        val revision = PostFixtures.revision(postId, PostFixtures.revisionId(1), content)

        database.inTransaction {
            repository.register(Post.register(postId, document), "Untitled", now)

            assertThatIllegalArgumentException().isThrownBy { repository.saveRevision(revision, emptyMap()) }
        }
    }

    @Test
    fun `one source revision is stored at most once per post`() {
        database.inTransaction {
            repository.register(Post.register(postId, document), "Untitled", now)
            repository.saveRevision(PostFixtures.revision(postId, PostFixtures.revisionId(1), sourceRevision = "same"), emptyMap())

            assertThatThrownBy {
                repository.saveRevision(PostFixtures.revision(postId, PostFixtures.revisionId(2), sourceRevision = "same"), emptyMap())
            }.isInstanceOf(ExposedSQLException::class.java)
                .hasMessageContaining("POST_REVISION_POST_SOURCE_REVISION_UNIQUE")
        }
    }

    @Test
    fun `saving a publication writes the first publication time only once`() {
        val revisionId = PostFixtures.revisionId(1)
        val firstPublication = Instant.parse("2026-10-16T03:00:00Z")

        database.inTransaction {
            repository.register(Post.register(postId, document), "Untitled", now)
            repository.saveRevision(PostFixtures.revision(postId, revisionId), emptyMap())
            val published = Post.register(postId, document).recordRevision(revisionId).publish(revisionId, firstPublication)
            repository.save(published, firstPublication)
            val generation = published.publication.generation
            val laterRecord = PostPublication(PostVisibility.PUBLIC, revisionId, false, generation, now.plusSeconds(60))
            repository.save(Post.restore(postId, document, revisionId, laterRecord), now)

            assertThat(repository.find(postId)?.firstPublishedAt).isEqualTo(firstPublication)
            assertThat(repository.find(postId)?.publication?.isPublic).isTrue()
        }
    }

    @Test
    fun `a legacy snapshot is copied unchanged and its files start unbound and are bound at most once`() {
        val legacyFile = PostFixtures.hosted("legacy.png", expiresAt = now)
        val snapshotJson = snapshotCodec.encode(BlockTree(listOf(PostFixtures.image("legacy", legacyFile))))
        val revisionId = PostFixtures.revisionId(9)

        database.inTransaction {
            seedLegacySnapshot(snapshotJson)
            repository.copyLegacySnapshot(postId, revisionId, setOf(legacyFile))
            storeMedia(listOf(key('c'), key('d')))
            repository.bindMissingMedia(revisionId, mapOf(sourceKey(legacyFile) to key('c')))
            repository.bindMissingMedia(revisionId, mapOf(sourceKey(legacyFile) to key('d')))

            val stored = PostRevisionTable.selectAll().where { PostRevisionTable.revisionId eq revisionId.value }.single()
            assertThat(stored[PostRevisionTable.contentJson]).isEqualTo(snapshotJson)
            assertThat(stored[PostRevisionTable.title]).isEqualTo("Legacy title")
            assertThat(repository.findMediaBindings(setOf(revisionId))).containsEntry(revisionId, mapOf(sourceKey(legacyFile) to key('c')))
        }
    }

    @Test
    fun `unbound legacy files are reported with their stored URL and expiry`() {
        val legacyFile = PostFixtures.hosted("legacy.png", expiresAt = now)
        val revisionId = PostFixtures.revisionId(9)

        database.inTransaction {
            seedLegacySnapshot(snapshotCodec.encode(BlockTree(listOf(PostFixtures.image("legacy", legacyFile)))))
            repository.copyLegacySnapshot(postId, revisionId, setOf(legacyFile))

            assertThat(repository.findUnboundMedia(setOf(revisionId))).containsEntry(revisionId, setOf(legacyFile))
            assertThat(repository.findMediaBindings(setOf(revisionId))).isEmpty()
        }
    }

    // id의 지역 사본이다. insert 블록 안에서는 테이블 자체의 postId 컬럼이 이 프로퍼티를 가린다.
    private fun seedLegacySnapshot(snapshotJson: String) {
        val legacyPostId = postId.value
        PostTable.insert {
            it[PostTable.postId] = legacyPostId
            it[title] = "Legacy title"
            it[createdAt] = now.atOffset(ZoneOffset.UTC)
            it[updatedAt] = now.atOffset(ZoneOffset.UTC)
        }
        PostSnapshotTable.insert {
            it[PostSnapshotTable.postId] = legacyPostId
            it[PostSnapshotTable.snapshotJson] = snapshotJson
            it[sourceRevision] = "legacy-rev"
            it[capturedAt] = now.atOffset(ZoneOffset.UTC)
        }
    }

    private fun storeMedia(keys: List<MediaKey>) {
        keys.forEach { mediaKey ->
            OwnedMediaTable.insert {
                it[OwnedMediaTable.mediaKey] = mediaKey.value
                it[contentType] = "image/png"
                it[byteSize] = 1
                it[bytes] = byteArrayOf(1)
                it[storedAt] = now.atOffset(ZoneOffset.UTC)
            }
        }
    }

    private fun key(digit: Char): MediaKey = MediaKey(digit.toString().repeat(64))

    private fun sourceKey(source: MediaSource.SourceHosted) = SourceMediaKey.of(source.url)
}
