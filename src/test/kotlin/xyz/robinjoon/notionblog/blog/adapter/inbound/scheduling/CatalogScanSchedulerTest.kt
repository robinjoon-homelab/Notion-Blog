package xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import xyz.robinjoon.notionblog.blog.application.model.CatalogSynchronization
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeCatalogUseCase

@ExtendWith(OutputCaptureExtension::class)
class CatalogScanSchedulerTest {
    private val catalogService = mockk<SynchronizeCatalogUseCase>()
    private val scheduler = CatalogScanScheduler(catalogService)

    @Test
    fun `reports registrations by count only`(output: CapturedOutput) {
        every { catalogService.synchronize() } returns CatalogSynchronization(registered = 2)

        scheduler.scan()

        assertThat(output.out + output.err).contains("Catalog scan registered 2 posts")
    }

    @Test
    fun `a failed scan is isolated and logged without its message`(output: CapturedOutput) {
        every { catalogService.synchronize() } throws IllegalStateException("token=do-not-log")

        scheduler.scan()

        assertThat(output.out + output.err)
            .contains("targetKind=CATALOG", "errorType=IllegalStateException")
            .doesNotContain("token=do-not-log")
    }
}
