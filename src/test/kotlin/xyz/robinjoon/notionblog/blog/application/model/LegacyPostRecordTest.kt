package xyz.robinjoon.notionblog.blog.application.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.post.PostPublication
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Instant

class LegacyPostRecordTest {
    private val revisionId = PostFixtures.revisionId(1)
    private val capturedAt = Instant.parse("2026-09-01T00:00:00Z")
    private val legacyFirst = Instant.parse("2026-08-01T00:00:00Z")

    @Test
    fun `a legacy public post stays public on its copied snapshot with its original first publication time`() {
        val post = record(public = true, firstPublishedAt = legacyFirst).toManagedPost(revisionId)

        assertThat(post.latestRevisionId).isEqualTo(revisionId)
        assertThat(post.publication).isEqualTo(PostPublication(PostVisibility.PUBLIC, revisionId, false, 0, legacyFirst))
    }

    @Test
    fun `a legacy public post without a first publication time takes its snapshot capture time`() {
        val post = record(public = true, firstPublishedAt = null).toManagedPost(revisionId)

        assertThat(post.firstPublishedAt).isEqualTo(capturedAt)
    }

    @Test
    fun `every other legacy post is private and keeps any history it had`() {
        val unpublished = record(public = false, firstPublishedAt = legacyFirst).toManagedPost(revisionId)
        val neverImported = record(public = false, firstPublishedAt = null).copy(snapshot = null).toManagedPost(null)

        assertThat(unpublished.publication).isEqualTo(PostPublication(PostVisibility.PRIVATE, null, false, 0, legacyFirst))
        assertThat(unpublished.latestRevisionId).isEqualTo(revisionId)
        assertThat(neverImported.publication).isEqualTo(PostPublication.initial())
        assertThat(neverImported.latestRevisionId).isNull()
    }

    private fun record(
        public: Boolean,
        firstPublishedAt: Instant?,
    ) = LegacyPostRecord(
        binding = PostSourceBinding(PostFixtures.postId(1), PostFixtures.document("legacy")),
        title = "Legacy",
        snapshot = LegacySnapshot(capturedAt, emptySet()),
        legacyPublic = public,
        firstPublishedAt = firstPublishedAt,
    )
}
