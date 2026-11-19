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
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.DuplicateSourceBindingException
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostMigrationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Instant
import java.util.UUID

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
        every { revisions.fetchRevision(document, selection, allowPartial = true) } throws SourceAccessException("private diagnostic")
        every { revisions.fetchRevision(nextDocument, selection, allowPartial = true) } returns nextImport
        every { media.captureForMigration(any()) } returns MigrationMediaCapture(CapturedMedia.empty)
        every { writer.apply(selection, nextImport, CapturedMedia.empty) } returns MigratePostResult.Imported(id)

        assertThat((service.migrate(command) as MigratePostResult.Failed).kind).isEqualTo(SyncFailureKind.ACCESS)
        assertThat(service.migrate(MigratePostCommand(selection, nextDocument))).isEqualTo(MigratePostResult.Imported(id))
    }

    @Test
    fun `body and media warnings accompany a partial draft with a distinct revision`() {
        val id = PostFixtures.postId(2)
        val bodyWarning = MigrationFailureDetail(MigrationFailureStage.BODY, "블록을 읽을 수 없습니다.", "NotionBlockMappingException")
        val mediaWarning = MigrationFailureDetail(MigrationFailureStage.MEDIA, "첨부 파일을 보관하지 못했습니다.", "MediaFetchException")
        val warnings = listOf(bodyWarning, mediaWarning)
        givenFreshDocument()
        every { revisions.fetchRevision(document, selection, allowPartial = true) } returns imported.copy(warnings = listOf(bodyWarning))
        every { media.captureForMigration(any()) } returns MigrationMediaCapture(CapturedMedia.empty, listOf(mediaWarning))
        val partial = imported.copy(sourceRevision = SourceRevision("revision:partial-migration"), warnings = warnings)
        every { writer.apply(selection, partial, CapturedMedia.empty) } returns MigratePostResult.Imported(id, warnings)

        assertThat(service.migrate(command)).isEqualTo(MigratePostResult.Imported(id, warnings))
    }

    @Test
    fun `mismatched source produces a mapping failure`() {
        givenFreshDocument()
        every { revisions.fetchRevision(document, selection, allowPartial = true) } returns
            imported.copy(sourceDocument = PostFixtures.document("wrong"))
        assertThat((service.migrate(command) as MigratePostResult.Failed).kind).isEqualTo(SyncFailureKind.MAPPING)
    }

    @Test
    fun `safe block details survive the migration failure without exposing foreign exception text`() {
        givenFreshDocument()
        val block = UUID.fromString("335e2d0a-976a-837d-b9f1-819ca3e19953")
        val diagnostic =
            MigrationFailureDetail(MigrationFailureStage.BODY, "번호 목록의 시작 번호가 올바르지 않습니다.", "NotionBlockMappingException", block)
        every { revisions.fetchRevision(document, selection, allowPartial = true) } throws
            SourceMappingException("private-token https://private.example", diagnostic = diagnostic)

        val result = service.migrate(command) as MigratePostResult.Failed

        assertThat(result.kind).isEqualTo(SyncFailureKind.MAPPING)
        assertThat(result.diagnostic).isEqualTo(diagnostic)
        assertThat(result.toString()).doesNotContain("private-token", "private.example")
    }

    @Test
    fun `an unclassified exception exposes a fixed fallback and known type instead of its message`() {
        givenFreshDocument()
        every { revisions.fetchRevision(document, selection, allowPartial = true) } throws
            SourceMappingException("foreign secret message", IllegalArgumentException("nested secret"))

        val diagnostic = requireNotNull((service.migrate(command) as MigratePostResult.Failed).diagnostic)

        assertThat(diagnostic.exceptionType).isEqualTo("SourceMappingException")
        assertThat(diagnostic.toString()).doesNotContain("foreign secret", "nested secret")
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
        every { revisions.fetchRevision(document, selection, allowPartial = true) } throws failure

        assertThatThrownBy { service.migrate(command) }.isSameAs(failure)
    }

    private fun givenFreshDocument() {
        every { queries.existing(document) } returns null
        every { revisions.fetchRevision(document, selection, allowPartial = true) } returns imported
        every { media.captureForMigration(any()) } returns MigrationMediaCapture(CapturedMedia.empty)
    }
}
