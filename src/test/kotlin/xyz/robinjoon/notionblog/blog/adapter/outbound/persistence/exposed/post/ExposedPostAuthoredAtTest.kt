package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedRevisionMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import java.time.Instant

class ExposedPostAuthoredAtTest {
    private val database = ExposedH2Database()
    private val repository = ExposedPostRepository(ExposedRevisionMapper(JsonBlockTreeSnapshotCodec(), JsonPostPropertiesCodec()))
    private val postId = PostFixtures.postId(1)
    private val document = PostFixtures.document("imported")
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val authoredAt = Instant.parse("2023-07-15T00:00:00Z")

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `registration preserves the original authored time and repeat registration leaves it unchanged`() {
        database.inTransaction {
            repository.register(Post.register(postId, document, authoredAt), "Imported", now)
            repository.register(Post.register(postId, document, now), "Other", now)

            assertThat(repository.find(postId)?.authoredAt).isEqualTo(authoredAt)
            assertThat(repository.findAllManaged().single().authoredAt).isEqualTo(authoredAt)
            assertThat(repository.lock(postId)?.authoredAt).isEqualTo(authoredAt)
            assertThat(repository.find(postId)?.firstPublishedAt).isNull()
        }
    }

    @Test
    fun `saving publication neither replaces nor adds an authored time`() {
        database.inTransaction {
            repository.register(Post.register(postId, document, authoredAt), "Imported", now)
            val revision = PostFixtures.revision(postId, PostFixtures.revisionId(1))
            repository.saveRevision(revision, emptyMap())
            val changed = Post.register(postId, document, now).recordRevision(revision.id).publish(revision.id, now)
            repository.save(changed, now)

            val existingId = PostFixtures.postId(2)
            val existing = Post.register(existingId, PostFixtures.document("existing"))
            repository.register(existing, "Existing", now)
            repository.save(existing.preserveAuthoredAt(authoredAt), now)

            assertThat(repository.find(postId)?.authoredAt).isEqualTo(authoredAt)
            assertThat(repository.find(postId)?.firstPublishedAt).isEqualTo(now)
            assertThat(repository.find(existingId)?.authoredAt).isNull()
        }
    }
}
