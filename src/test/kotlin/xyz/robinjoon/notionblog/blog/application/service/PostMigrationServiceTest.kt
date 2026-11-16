package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.CapturedMedia
import xyz.robinjoon.notionblog.blog.application.model.ImportedRevision
import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationCatalogPage
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.DuplicateSourceBindingException
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostMigrationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Instant

class PostMigrationServiceTest {
    private val catalog = mockk<PostMigrationSource>()
    private val revisions = mockk<PostRevisionSource>()
    private val queries = mockk<MigrationPostQueryService>()
    private val media = mockk<CapturePostMediaService>()
    private val writer = mockk<ApplyMigratedPostService>()
    private val failures = mockk<OperationFailureReporter>(relaxUnitFun = true)
    private val service = PostMigrationService(catalog, revisions, queries, media, writer, failures)
    private val selection = MigrationSelection("source", MigrationFields("title", authoredAtPropertyId = "date"))
    private val document = PostFixtures.document("old-post")
    private val command = MigratePostCommand(selection, document)
    private val imported =
        ImportedRevision(
            document,
            PostFixtures.properties(),
            SourceRevision("revision"),
            PostFixtures.paragraphs("old body"),
            Instant.parse("2023-07-15T00:00:00Z"),
        )

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `preparation traverses pages and includes each source document once`() {
        val first = MigrationEntry(document, "Old post")
        val second = MigrationEntry(PostFixtures.document("second"), "Second")
        every { catalog.readCatalog(selection, null) } returns MigrationCatalogPage(listOf(first), "next")
        every { catalog.readCatalog(selection, "next") } returns MigrationCatalogPage(listOf(first, second), null)

        assertThat(service.prepare(selection).entries).containsExactly(first, second)
    }

    @Test
    fun `preparation rejects a repeated cursor without returning an incomplete plan`() {
        every { catalog.readCatalog(selection, any()) } returns MigrationCatalogPage(emptyList(), "same")

        assertThatThrownBy { service.prepare(selection) }
            .isInstanceOf(MigrationSourceException::class.java)
            .hasCauseInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `an existing source is skipped and keeps its original blog id`() {
        val id = PostFixtures.postId(1)
        every { queries.existing(document) } returns id

        assertThat(service.migrate(command)).isEqualTo(MigratePostResult.Skipped(id))
    }

    @Test
    fun `a fetched post with its original date becomes an imported draft result`() {
        val id = PostFixtures.postId(2)
        givenFreshDocument()
        every { writer.apply(selection, imported, CapturedMedia.empty) } returns MigratePostResult.Imported(id)

        assertThat(service.migrate(command)).isEqualTo(MigratePostResult.Imported(id))
    }

    @Test
    fun `one failed post does not prevent a later post from being imported`() {
        val nextDocument = PostFixtures.document("next")
        val nextImport = imported.copy(sourceDocument = nextDocument)
        val id = PostFixtures.postId(2)
        every { queries.existing(any()) } returns null
        every { revisions.fetchRevision(document, selection) } throws SourceAccessException("private diagnostic")
        every { revisions.fetchRevision(nextDocument, selection) } returns nextImport
        every { media.capture(any(), any()) } returns CapturedMedia.empty
        every { writer.apply(selection, nextImport, CapturedMedia.empty) } returns MigratePostResult.Imported(id)

        assertThat(service.migrate(command)).isEqualTo(MigratePostResult.Failed(SyncFailureKind.ACCESS))
        assertThat(service.migrate(MigratePostCommand(selection, nextDocument))).isEqualTo(MigratePostResult.Imported(id))
    }

    @Test
    fun `media failure and mismatched source produce distinct failure results`() {
        givenFreshDocument()
        every { media.capture(any(), any()) } throws MediaFetchException("file too big")
        assertThat(service.migrate(command)).isEqualTo(MigratePostResult.Failed(SyncFailureKind.MEDIA))

        every { revisions.fetchRevision(document, selection) } returns imported.copy(sourceDocument = PostFixtures.document("wrong"))
        assertThat(service.migrate(command)).isEqualTo(MigratePostResult.Failed(SyncFailureKind.MAPPING))
    }

    @Test
    fun `a source bound by another migration is skipped after the conflicting transaction`() {
        val id = PostFixtures.postId(3)
        givenFreshDocument()
        every { queries.existing(document) } returnsMany listOf(null, id)
        every { writer.apply(any(), any(), any()) } throws DuplicateSourceBindingException("bound")

        assertThat(service.migrate(command)).isEqualTo(MigratePostResult.Skipped(id))
    }

    @Test
    fun `an interrupted source failure propagates instead of continuing the queue`() {
        val failure = SourceAccessException("cancelled", InterruptedException("stop"))
        givenFreshDocument()
        every { revisions.fetchRevision(document, selection) } throws failure

        assertThatThrownBy { service.migrate(command) }.isSameAs(failure)
    }

    private fun givenFreshDocument() {
        every { queries.existing(document) } returns null
        every { revisions.fetchRevision(document, selection) } returns imported
        every { media.capture(any(), any()) } returns CapturedMedia.empty
    }
}
