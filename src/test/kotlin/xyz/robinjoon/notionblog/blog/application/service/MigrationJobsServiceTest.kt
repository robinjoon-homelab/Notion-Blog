package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.ClaimedMigrationItem
import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationPlan
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.PostMigrationUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.util.UUID

class MigrationJobsServiceTest {
    private val migration = mockk<PostMigrationUseCase>()
    private val state = mockk<MigrationJobStateService>()
    private val service = MigrationJobsService(migration, state)
    private val selection = MigrationSelection("data-source", MigrationFields("title"))
    private val document = PostFixtures.document("old-post")
    private val jobId = UUID.fromString("89faf91f-d172-4ba3-955a-fbd3b38e970f")

    @Test
    fun `returns the accepted job id after preparing the source and saving its queue`() {
        val plan = MigrationPlan(selection, listOf(MigrationEntry(document, "Old post")))
        every { migration.prepare(selection) } returns plan
        every { state.create(plan) } returns jobId

        assertThat(service.start(selection)).isEqualTo(jobId)
    }

    @Test
    fun `an interrupted worker propagates the failure so the item can be reclaimed`() {
        val item = ClaimedMigrationItem(jobId, 0, jobId, MigratePostCommand(selection, document))
        val interruption = SourceAccessException("interrupted", InterruptedException())
        every { state.claim() } returns item
        every { migration.migrate(item.command, item) } throws interruption

        assertThatThrownBy { service.runNext() }.isSameAs(interruption)
    }
}
