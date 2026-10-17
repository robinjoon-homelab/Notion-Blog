package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedMediaStorage
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.OwnedMediaTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows
import java.time.Instant

class ExposedMediaStorageTest {
    private val database = ExposedH2Database()
    private val storage = ExposedMediaStorage()
    private val publishedKey = MediaKey("a".repeat(64))
    private val draftKey = MediaKey("b".repeat(64))
    private val postId = PostFixtures.postId(1)

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `stores bytes by key and saving the same key again keeps one copy`() {
        database.inTransaction {
            storage.save(listOf(fetched(publishedKey, byteArrayOf(1, 2)), fetched(publishedKey, byteArrayOf(1, 2))), Instant.EPOCH)
            storage.save(listOf(fetched(draftKey, byteArrayOf(3))), Instant.EPOCH)
            seedPost(PostVisibility.PUBLIC)

            assertThat(storage.findPublic(publishedKey)?.bytes).containsExactly(1, 2)
            assertThat(storage.findPublic(publishedKey)?.contentType).isEqualTo("image/png")
        }
    }

    @Test
    fun `saving a stored key again keeps its bytes content type and stored time`() {
        val firstStoredAt = Instant.parse("2026-10-01T00:00:00Z")

        database.inTransaction {
            storage.save(listOf(fetched(publishedKey, byteArrayOf(1, 2))), firstStoredAt)
            val sameBytesAsGif =
                FetchedMedia(SourceMediaKey("https://files.example.com/other.gif"), publishedKey, "image/gif", byteArrayOf(1, 2))
            storage.save(listOf(sameBytesAsGif), Instant.parse("2026-10-09T00:00:00Z"))

            val stored = OwnedMediaTable.selectAll().single()
            assertThat(stored[OwnedMediaTable.bytes]).containsExactly(1, 2)
            assertThat(stored[OwnedMediaTable.contentType]).isEqualTo("image/png")
            assertThat(stored[OwnedMediaTable.storedAt].toInstant()).isEqualTo(firstStoredAt)
        }
    }

    @Test
    fun `public files are only those of a public post's published revision`() {
        database.inTransaction {
            storage.save(listOf(fetched(publishedKey, byteArrayOf(1)), fetched(draftKey, byteArrayOf(2))), Instant.EPOCH)
            seedPost(PostVisibility.PUBLIC)

            assertThat(storage.findPublic(publishedKey)).isNotNull()
            assertThat(storage.findPublic(draftKey)).isNull()
            assertThat(storage.findForPreview(draftKey)?.bytes).containsExactly(2)
            assertThat(storage.findForPreview(MediaKey("c".repeat(64)))).isNull()
        }
    }

    @Test
    fun `unpublishing stops serving the published files immediately`() {
        database.inTransaction {
            storage.save(listOf(fetched(publishedKey, byteArrayOf(1)), fetched(draftKey, byteArrayOf(2))), Instant.EPOCH)
            seedPost(PostVisibility.PUBLIC)
            PostPublicationTable.update({ PostPublicationTable.postId eq postId.value }) { it[visibility] = PostVisibility.PRIVATE.name }

            assertThat(storage.findPublic(publishedKey)).isNull()
            assertThat(storage.findForPreview(publishedKey)).isNotNull()
        }
    }

    // 저장된 게시 파일과 저장된 초안 파일을 연결한다. 연결은 외래 키로 저장된 바이트를 참조한다.
    private fun seedPost(visibility: PostVisibility) {
        PostRows.post(postId, PostFixtures.document("doc"), Instant.parse("2026-10-01T00:00:00Z"))
        PostRows.revision(postId, PostFixtures.revisionId(1), "Published", "{}")
        PostRows.revision(postId, PostFixtures.revisionId(2), "Draft", "{}")
        PostRows.publication(postId, visibility, PostFixtures.revisionId(2), PostFixtures.revisionId(1))
        PostRows.binding(PostFixtures.revisionId(1), "https://files.example.com/published.png", publishedKey)
        PostRows.binding(PostFixtures.revisionId(2), "https://files.example.com/draft.png", draftKey)
    }

    private fun fetched(
        key: MediaKey,
        bytes: ByteArray,
    ) = FetchedMedia(SourceMediaKey("https://files.example.com/${key.value.first()}.png"), key, "image/png", bytes)
}
