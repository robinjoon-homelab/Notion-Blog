package xyz.robinjoon.notionblog.blog.adapter.inbound.startup

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import xyz.robinjoon.notionblog.blog.application.model.LegacyMigrationResult
import xyz.robinjoon.notionblog.blog.application.port.input.MigrateLegacyPostsUseCase

@ExtendWith(OutputCaptureExtension::class)
class LegacyPostMigrationInitializerTest {
    private val migration = mockk<MigrateLegacyPostsUseCase>()
    private val initializer = LegacyPostMigrationInitializer(migration)

    @Test
    fun `a failed migration stops the application from starting`() {
        val failure = IllegalStateException("database unavailable")
        every { migration.migrate() } throws failure

        assertThatThrownBy { initializer.afterSingletonsInstantiated() }.isSameAs(failure)
    }

    @Test
    fun `reports how many legacy posts were brought under management`(output: CapturedOutput) {
        every { migration.migrate() } returns LegacyMigrationResult(migrated = 3, madePublic = 1)

        initializer.afterSingletonsInstantiated()

        assertThat(output.out + output.err).contains("Brought 3 legacy posts under management; 1 stay public")
    }
}
