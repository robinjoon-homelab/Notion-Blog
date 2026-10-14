package xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.io.IOException
import java.lang.reflect.UndeclaredThrowableException

@ExtendWith(OutputCaptureExtension::class)
class SynchronizationFailureHandlerTest {
    private val handler = SynchronizationFailureHandler("POST")

    @AfterEach
    fun clearInterruption() {
        Thread.interrupted()
    }

    @Test
    fun `runtime failure is logged by kind and type only and does not propagate`(output: CapturedOutput) {
        handler.handleError(IllegalStateException("token=do-not-log"))

        assertThat(Thread.currentThread().isInterrupted).isFalse()
        assertThat(output.out + output.err)
            .contains("Synchronization target failed", "targetKind=POST", "errorType=IllegalStateException")
            .doesNotContain("token=do-not-log")
    }

    @Test
    fun `errors and checked exceptions propagate unchanged without logging`(output: CapturedOutput) {
        val fatal = AssertionError("fatal")
        val checked = IOException("checked")

        assertThatThrownBy { handler.handleError(fatal) }.isSameAs(fatal)
        assertThatThrownBy { handler.handleError(checked) }.isSameAs(checked)
        assertThat(output.out + output.err).doesNotContain("Synchronization target failed")
    }

    @Test
    fun `direct interruption propagates unchanged`() {
        val interruption = InterruptedException("stop")

        assertThatThrownBy { handler.handleError(interruption) }.isSameAs(interruption)
    }

    @Test
    fun `runtime failure caused by interruption restores the flag and propagates unchanged`(output: CapturedOutput) {
        val failure = IllegalStateException("cancelled", RuntimeException(InterruptedException("stop")))

        assertThatThrownBy { handler.handleError(failure) }.isSameAs(failure)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(output.out + output.err).doesNotContain("Synchronization target failed")
    }

    @Test
    fun `runtime failure on an interrupted thread keeps the flag and propagates unchanged`() {
        val failure = IllegalStateException("late")
        Thread.currentThread().interrupt()

        assertThatThrownBy { handler.handleError(failure) }.isSameAs(failure)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }

    @Test
    fun `a cyclic cause chain ends the interruption search`(output: CapturedOutput) {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        handler.handleError(first)

        assertThat(output.out + output.err).contains("errorType=IllegalStateException")
    }

    @Test
    fun `an undeclared wrapper without a cause is isolated as the original runtime failure`(output: CapturedOutput) {
        handler.run { throw UndeclaredThrowableException(null) }

        assertThat(Thread.currentThread().isInterrupted).isFalse()
        assertThat(output.out + output.err).contains("targetKind=POST", "errorType=UndeclaredThrowableException")
    }

    @Test
    fun `an undeclared wrapper on an interrupted thread propagates the same wrapper with the flag kept`() {
        val wrapper = UndeclaredThrowableException(IOException("checked"))
        Thread.currentThread().interrupt()

        assertThatThrownBy { handler.run { throw wrapper } }.isSameAs(wrapper)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }

    @Test
    fun `a reused handler keeps no earlier failure for later successes or failures`(output: CapturedOutput) {
        val fatal = AssertionError("fatal")

        handler.run { throw UndeclaredThrowableException(IOException("first")) }
        handler.run { }
        assertThatThrownBy { handler.run { throw fatal } }.isSameAs(fatal)
        handler.run { throw UndeclaredThrowableException(null) }

        assertThat(Regex("errorType=UndeclaredThrowableException").findAll(output.out + output.err).count()).isEqualTo(2)
    }
}
