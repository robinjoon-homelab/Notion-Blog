package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.CapturedMedia
import xyz.robinjoon.notionblog.blog.application.model.ClaimedMigrationItem
import xyz.robinjoon.notionblog.blog.application.model.ImportedRevision
import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MigrationJobRepository
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ApplyMigratedPostServiceTest {
    @Test
    fun `a worker with a replaced claim cannot save a draft`() {
        val selection = MigrationSelection("source", MigrationFields("title"))
        val source = PostFixtures.document("old-post")
        val jobId = UUID.fromString("89faf91f-d172-4ba3-955a-fbd3b38e970f")
        val claim = ClaimedMigrationItem(jobId, 0, jobId, MigratePostCommand(selection, source))
        val imported = ImportedRevision(source, PostFixtures.properties(), SourceRevision("revision"), PostFixtures.paragraphs("body"))
        val jobs = mockk<MigrationJobRepository>()
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        val writer = ApplyMigratedPostService(mockk(), mockk(), mockk(), mockk(), clock, jobs)
        every { jobs.lockClaim(claim) } returns false

        assertThat(writer.apply(selection, imported, CapturedMedia.empty, claim)).isNull()
    }
}
