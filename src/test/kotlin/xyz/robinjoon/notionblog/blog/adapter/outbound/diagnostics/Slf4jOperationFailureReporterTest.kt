package xyz.robinjoon.notionblog.blog.adapter.outbound.diagnostics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.RecoveredOperation
import java.io.IOException
import java.net.SocketTimeoutException

@ExtendWith(OutputCaptureExtension::class)
class Slf4jOperationFailureReporterTest {
    private val reporter = Slf4jOperationFailureReporter()

    @ParameterizedTest
    @EnumSource(RecoveredOperation::class)
    fun `records the operation and the failure and root cause types without sensitive exception details`(
        operation: RecoveredOperation,
        output: CapturedOutput,
    ) {
        val root = SocketTimeoutException("private root https://files.example.com/a.png?X-Amz-Signature=private-signature")
        val failure = SensitiveFailure(IOException("private middle message", root))

        reporter.report(operation, failure)

        assertThat(output.out + output.err)
            .contains(
                "operation=${operation.name}",
                "errorType=${SensitiveFailure::class.java.name}",
                "rootCauseType=${SocketTimeoutException::class.java.name}",
            ).doesNotContain(
                "private failure message",
                "private middle message",
                "private root",
                "https://files.example.com",
                "private-signature",
                "token=private-token",
                "private toString output",
                "\tat ",
            )
    }

    @Test
    fun `records a failure without a cause as having no root cause`(output: CapturedOutput) {
        reporter.report(RecoveredOperation.POST_REFRESH, IllegalStateException("private refresh failure"))

        assertThat(output.out + output.err)
            .contains(
                "operation=POST_REFRESH",
                "errorType=${IllegalStateException::class.java.name}",
                "rootCauseType=none",
            ).doesNotContain("private refresh failure", "\tat ")
    }

    @Test
    fun `a cyclic cause chain ends at the last cause not seen before`(output: CapturedOutput) {
        val inner = IllegalArgumentException("inner")
        val failure = IllegalStateException("outer", inner)
        inner.initCause(failure)

        reporter.report(RecoveredOperation.POST_REFRESH, failure)

        assertThat(output.out + output.err)
            .contains("errorType=${IllegalStateException::class.java.name}", "rootCauseType=${IllegalArgumentException::class.java.name}")
    }

    private class SensitiveFailure(
        cause: Throwable,
    ) : IllegalStateException("private failure message token=private-token", cause) {
        override fun toString(): String = "private toString output"
    }
}
