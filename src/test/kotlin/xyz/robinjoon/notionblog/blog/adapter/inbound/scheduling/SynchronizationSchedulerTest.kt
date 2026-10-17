package xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizationQueryUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizePostUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeSiteConfigurationUseCase
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.io.IOException
import java.lang.reflect.UndeclaredThrowableException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(OutputCaptureExtension::class)
class SynchronizationSchedulerTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val queryService = mockk<SynchronizationQueryUseCase>()
    private val siteConfigurationService = mockk<SynchronizeSiteConfigurationUseCase>()
    private val postService = mockk<SynchronizePostUseCase>()

    @Test
    fun `isolates a runtime failure to its target and does not log exception message`(output: CapturedOutput) {
        val publicationId = PublicationId(UUID.randomUUID())
        val postId = PostId(UUID.randomUUID())
        every { queryService.findDueTargets(now, 7) } returns
            listOf(
                SyncTarget.SiteConfiguration,
                SyncTarget.Publication(publicationId),
                SyncTarget.Post(postId),
            )
        every { siteConfigurationService.synchronize() } throws RuntimeException("token=do-not-log")
        every { postService.synchronize(postId) } just Runs

        scheduler(dueBatchSize = 7).synchronizeDue()

        val logs = output.out + output.err
        assertThat(logs)
            .contains("Synchronization target failed", "targetKind=SITE_CONFIGURATION", "errorType=RuntimeException")
            .doesNotContain("token=do-not-log")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `wrapped interruption restores interrupt state and propagates the original failure`(duringLookup: Boolean) {
        val failure = IllegalStateException("cancelled", IllegalStateException(InterruptedException("stop")))
        val instance = scheduler(dueBatchSize = 7)
        failSynchronizationAtBoundary(duringLookup, failure)

        try {
            assertThatThrownBy { instance.synchronizeDue() }.isSameAs(failure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @ParameterizedTest
    @CsvSource("true, true", "true, false", "false, true", "false, false")
    fun `direct interruption and fatal errors propagate from either boundary`(
        duringLookup: Boolean,
        interrupted: Boolean,
    ) {
        val failure = if (interrupted) InterruptedException("stop") else AssertionError("fatal")
        val instance = scheduler(dueBatchSize = 7)
        failSynchronizationAtBoundary(duringLookup, failure)

        try {
            assertThatThrownBy { instance.synchronizeDue() }.isSameAs(failure)
        } finally {
            Thread.interrupted()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an undeclared checked failure is isolated as the original runtime wrapper`(
        duringLookup: Boolean,
        output: CapturedOutput,
    ) {
        val instance = scheduler(dueBatchSize = 7)
        failSynchronizationAtBoundary(duringLookup, UndeclaredThrowableException(IOException("token=do-not-log")))
        every { postService.synchronize(any()) } just Runs

        instance.synchronizeDue()

        assertThat(Thread.currentThread().isInterrupted).isFalse()
        assertThat(output.out + output.err)
            .contains("targetKind=${if (duringLookup) "LOOKUP" else "SITE_CONFIGURATION"}", "errorType=UndeclaredThrowableException")
            .doesNotContain("token=do-not-log")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an undeclared wrapper without a cause is isolated at either boundary`(
        duringLookup: Boolean,
        output: CapturedOutput,
    ) {
        val instance = scheduler(dueBatchSize = 7)
        failSynchronizationAtBoundary(duringLookup, UndeclaredThrowableException(null))
        every { postService.synchronize(any()) } just Runs

        instance.synchronizeDue()

        assertThat(Thread.currentThread().isInterrupted).isFalse()
        assertThat(output.out + output.err)
            .contains("targetKind=${if (duringLookup) "LOOKUP" else "SITE_CONFIGURATION"}", "errorType=UndeclaredThrowableException")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an undeclared interruption restores interrupt state and propagates the same wrapper`(duringLookup: Boolean) {
        val failure = UndeclaredThrowableException(InterruptedException("stop"))
        val instance = scheduler(dueBatchSize = 7)
        failSynchronizationAtBoundary(duringLookup, failure)

        try {
            assertThatThrownBy { instance.synchronizeDue() }.isSameAs(failure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `lookup runtime failure is isolated and logged without its message`(output: CapturedOutput) {
        val instance = scheduler(dueBatchSize = 7)
        every { queryService.findDueTargets(now, 7) } throws IllegalStateException("token=do-not-log")

        instance.synchronizeDue()

        assertThat(output.out + output.err)
            .contains("targetKind=LOOKUP", "errorType=IllegalStateException")
            .doesNotContain("token=do-not-log")
    }

    @Test
    fun `requires a positive due batch size`() {
        assertThatThrownBy { scheduler(dueBatchSize = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun scheduler(dueBatchSize: Int) =
        SynchronizationScheduler(
            queryService = queryService,
            siteConfigurationService = siteConfigurationService,
            postService = postService,
            clock = clock,
            dueBatchSize = dueBatchSize,
        )

    private fun failSynchronizationAtBoundary(
        duringLookup: Boolean,
        failure: Throwable,
    ) {
        if (duringLookup) {
            every { queryService.findDueTargets(now, 7) } throws failure
            return
        }
        every { queryService.findDueTargets(now, 7) } returns
            listOf(
                SyncTarget.SiteConfiguration,
                SyncTarget.Post(PostId(UUID.randomUUID())),
            )
        every { siteConfigurationService.synchronize() } throws failure
    }
}
