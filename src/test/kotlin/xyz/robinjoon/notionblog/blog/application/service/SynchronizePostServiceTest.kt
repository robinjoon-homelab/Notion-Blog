package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.CapturedMedia
import xyz.robinjoon.notionblog.blog.application.model.ImportedRevision
import xyz.robinjoon.notionblog.blog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.blog.application.model.RefreshPostResult
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.support.fixture.PostFixtures

class SynchronizePostServiceTest {
    private val queries = mockk<SynchronizationQueryService>()
    private val source = mockk<PostRevisionSource>()
    private val media = mockk<CapturePostMediaService>()
    private val applyService = mockk<ApplyImportedPostService>(relaxUnitFun = true)
    private val cancelService = mockk<CancelSynchronizationService>(relaxUnitFun = true)
    private val failures = mockk<OperationFailureReporter>(relaxUnitFun = true)
    private val service = SynchronizePostService(queries, source, media, applyService, cancelService, failures)
    private val postId = PostFixtures.postId(1)
    private val document = PostFixtures.document("doc")

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `an unmanaged or archived post is not found`() {
        every { queries.loadPost(any()) } returns null

        assertThat(service.refresh(postId)).isEqualTo(RefreshPostResult.NotFound)
    }

    @Test
    fun `a stored import is returned as the refresh result`() {
        givenContext(latest = "rev-1")
        every { source.fetchRevision(any()) } returns imported("rev-2")
        every { media.capture(any(), any()) } returns CapturedMedia.empty
        every { applyService.apply(any(), any(), any()) } returns RefreshPostResult.Updated(PostFixtures.revisionId(2))

        assertThat(service.refresh(postId)).isEqualTo(RefreshPostResult.Updated(PostFixtures.revisionId(2)))
    }

    @Test
    fun `source and file failures become failure kinds for the admin`() {
        val failure = SourceAccessException("page is in the trash")
        givenContext(latest = "rev-1")
        every { source.fetchRevision(any()) } throws failure

        assertThat(service.refresh(postId)).isEqualTo(RefreshPostResult.Failed(SyncFailureKind.ACCESS))

        val withFile = BlockTree(listOf(PostFixtures.image("i", PostFixtures.hosted("x.png"))))
        every { source.fetchRevision(any()) } returns imported("rev-2", withFile)
        every { media.capture(any(), any()) } throws MediaFetchException("too large")

        assertThat(service.refresh(postId)).isEqualTo(RefreshPostResult.Failed(SyncFailureKind.MEDIA))
    }

    @Test
    fun `a document other than the requested one is a mapping failure`() {
        givenContext(latest = "rev-1")
        every { source.fetchRevision(any()) } returns imported("rev-2").copy(sourceDocument = PostFixtures.document("other"))

        assertThat(service.refresh(postId)).isEqualTo(RefreshPostResult.Failed(SyncFailureKind.MAPPING))
    }

    @Test
    fun `an interrupted import propagates the same failure instead of returning a failure kind`() {
        val interrupted = MediaFetchException("cancelled", InterruptedException("stop"))
        givenContext(latest = "rev-1")
        every { source.fetchRevision(any()) } returns imported("rev-2")
        every { media.capture(any(), any()) } throws interrupted

        assertThatThrownBy { service.refresh(postId) }.isSameAs(interrupted)
    }

    private fun givenContext(latest: String) {
        every { queries.loadPost(any()) } returns PostSynchronizationContext(postId, document, SourceRevision(latest), emptyList())
    }

    private fun imported(
        sourceRevision: String,
        content: BlockTree = PostFixtures.paragraphs("Body"),
    ) = ImportedRevision(document, PostFixtures.properties(), SourceRevision(sourceRevision), content)
}
