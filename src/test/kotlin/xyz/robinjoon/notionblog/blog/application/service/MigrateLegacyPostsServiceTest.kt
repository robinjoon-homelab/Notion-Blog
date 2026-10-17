package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.LegacyMigrationResult
import xyz.robinjoon.notionblog.blog.application.model.LegacyPostRecord
import xyz.robinjoon.notionblog.blog.application.model.LegacySnapshot
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.LegacyPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

class MigrateLegacyPostsServiceTest {
    private val legacy = mockk<LegacyPostStore>()
    private val posts = mockk<PostRepository>()
    private val syncStates = mockk<SyncStateRepository>()
    private val recorder = SyncStateRecorder(syncStates, RefreshPolicy(Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofMinutes(5)))
    private val service =
        MigrateLegacyPostsService(legacy, posts, recorder, Clock.fixed(PostFixtures.capturedAt, ZoneOffset.UTC)) {
            PostFixtures.revisionId(1)
        }

    @Test
    fun `manages every legacy bound post and keeps only the legacy public ones public`() {
        every { legacy.findUnmanaged() } returns
            listOf(record(1, public = true), record(2, public = false), record(3, public = false, snapshot = false))
        every { posts.copyLegacySnapshot(any(), any(), any()) } just Runs
        every { posts.register(any(), any(), any()) } just Runs
        every { syncStates.find(any()) } returns null
        every { syncStates.save(any()) } just Runs

        assertThat(service.migrate()).isEqualTo(LegacyMigrationResult(migrated = 3, madePublic = 1))
    }

    @Test
    fun `nothing is migrated again once every post is managed`() {
        every { legacy.findUnmanaged() } returns emptyList()

        assertThat(service.migrate()).isEqualTo(LegacyMigrationResult(migrated = 0, madePublic = 0))
    }

    private fun record(
        label: Int,
        public: Boolean,
        snapshot: Boolean = true,
    ) = LegacyPostRecord(
        binding = PostSourceBinding(PostFixtures.postId(label), PostFixtures.document("legacy-$label")),
        title = "Legacy $label",
        snapshot = LegacySnapshot(PostFixtures.capturedAt, emptySet()).takeIf { snapshot },
        legacyPublic = public,
        firstPublishedAt = null,
    )
}
