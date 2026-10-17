package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaGateway
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Clock
import java.time.ZoneOffset

class CapturePostMediaServiceTest {
    private val gateway = mockk<MediaGateway>()
    private val failures = mockk<OperationFailureReporter>(relaxUnitFun = true)
    private val clock = Clock.fixed(PostFixtures.capturedAt, ZoneOffset.UTC)
    private val service = CapturePostMediaService(gateway, maxImportBytes = 10, failures, clock)

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `downloads each required file once per source file name`() {
        val first = PostFixtures.hosted("a.png", signature = "1")
        val sameFile = PostFixtures.hosted("a.png", signature = "2")
        val other = PostFixtures.hosted("b.png")
        every { gateway.fetch(first) } returns fetched(first, 'a', 3)
        every { gateway.fetch(sameFile) } returns fetched(sameFile, 'a', 3)
        every { gateway.fetch(other) } returns fetched(other, 'b', 3)

        val captured = service.capture(listOf(first, sameFile, other), emptyMap())

        assertThat(captured.newRevision.map { it.mediaKey }).containsExactly(key('a'), key('b'))
        assertThat(captured.repairs).isEmpty()
    }

    @Test
    fun `one file name serving different bytes is rejected`() {
        val first = PostFixtures.hosted("a.png", signature = "1")
        val second = PostFixtures.hosted("a.png", signature = "2")
        every { gateway.fetch(first) } returns fetched(first, 'a', 1)
        every { gateway.fetch(second) } returns fetched(second, 'b', 1)

        assertThatThrownBy { service.capture(listOf(first, second), emptyMap()) }.isInstanceOf(MediaFetchException::class.java)
    }

    @Test
    fun `required files over the import total fail the whole import`() {
        val first = PostFixtures.hosted("a.png")
        val second = PostFixtures.hosted("b.png")
        every { gateway.fetch(first) } returns fetched(first, 'a', 6)
        every { gateway.fetch(second) } returns fetched(second, 'b', 6)

        assertThatThrownBy { service.capture(listOf(first, second), emptyMap()) }
            .isInstanceOf(MediaFetchException::class.java)
            .hasMessageContaining("total size")
    }

    @Test
    fun `a legacy repair that fails or no longer fits is left missing and stays with its revision`() {
        val required = PostFixtures.hosted("required.png")
        val failing = PostFixtures.hosted("failing.png")
        val tooLarge = PostFixtures.hosted("large.png")
        val fitting = PostFixtures.hosted("fits.png")
        every { gateway.fetch(required) } returns fetched(required, 'a', 6)
        every { gateway.fetch(failing) } throws MediaFetchException("gone")
        every { gateway.fetch(tooLarge) } returns fetched(tooLarge, 'b', 5)
        every { gateway.fetch(fitting) } returns fetched(fitting, 'c', 4)

        val legacyRevision = PostFixtures.revisionId(1)
        val captured = service.capture(listOf(required), mapOf(legacyRevision to listOf(failing, tooLarge, fitting)))

        assertThat(captured.newRevision.map { it.mediaKey }).containsExactly(key('a'))
        assertThat(captured.repairs.getValue(legacyRevision).map { it.mediaKey }).containsExactly(key('c'))
    }

    @Test
    fun `a legacy repair whose stored URL has expired is left missing while every required file is still downloaded`() {
        val required = PostFixtures.hosted("required.png", expiresAt = PostFixtures.capturedAt.minusSeconds(1))
        val expired = PostFixtures.hosted("expired.png", expiresAt = PostFixtures.capturedAt.minusSeconds(1))
        val expiringNow = PostFixtures.hosted("now.png", expiresAt = PostFixtures.capturedAt)
        val valid = PostFixtures.hosted("valid.png", expiresAt = PostFixtures.capturedAt.plusSeconds(60))
        val withoutExpiry = PostFixtures.hosted("unknown.png")
        every { gateway.fetch(any()) } returns fetched(expired, 'e', 1)
        every { gateway.fetch(required) } returns fetched(required, 'a', 1)
        every { gateway.fetch(valid) } returns fetched(valid, 'b', 1)
        every { gateway.fetch(withoutExpiry) } returns fetched(withoutExpiry, 'c', 1)

        val legacyRevision = PostFixtures.revisionId(1)
        val captured = service.capture(listOf(required), mapOf(legacyRevision to listOf(expired, expiringNow, valid, withoutExpiry)))

        assertThat(captured.newRevision.map { it.mediaKey }).containsExactly(key('a'))
        assertThat(captured.repairs.getValue(legacyRevision).map { it.mediaKey }).containsExactlyInAnyOrder(key('b'), key('c'))
    }

    @Test
    fun `an interrupted optional download is not treated as an ordinary failure`() {
        val optional = PostFixtures.hosted("legacy.png")
        val interrupted = MediaFetchException("cancelled", InterruptedException("stop"))
        every { gateway.fetch(optional) } throws interrupted

        assertThatThrownBy { service.capture(emptyList(), mapOf(PostFixtures.revisionId(1) to listOf(optional))) }.isSameAs(interrupted)
    }

    private fun fetched(
        source: MediaSource.SourceHosted,
        digit: Char,
        size: Int,
    ) = FetchedMedia(SourceMediaKey.of(source.url), key(digit), "image/png", ByteArray(size) { 1 })

    private fun key(digit: Char) = MediaKey(digit.toString().repeat(64))
}
