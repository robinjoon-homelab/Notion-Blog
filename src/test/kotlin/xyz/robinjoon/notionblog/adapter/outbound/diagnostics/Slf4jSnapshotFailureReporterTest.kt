package xyz.robinjoon.notionblog.adapter.outbound.diagnostics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.domain.post.PostId
import java.util.UUID

@ExtendWith(OutputCaptureExtension::class)
class Slf4jSnapshotFailureReporterTest {
    private val reporter = Slf4jSnapshotFailureReporter()
    private val postId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @ParameterizedTest
    @EnumSource(SnapshotFailureOperation::class)
    fun `records operation internal identity and failure types without sensitive exception details`(
        operation: SnapshotFailureOperation,
        output: CapturedOutput,
    ) {
        val cause = SensitiveSnapshotCause()
        val failure = SnapshotContentException("private snapshot body https://private.example/?token=private-token", cause)

        reporter.report(failure, operation, postId)

        assertThat(output.out + output.err)
            .contains(
                "operation=${operation.name}",
                "postId=${postId.value}",
                "errorType=${SnapshotContentException::class.java.name}",
                "causeType=${SensitiveSnapshotCause::class.java.name}",
            ).doesNotContain(
                "private snapshot body",
                "https://private.example",
                "private-token",
                "private cause message",
                "private root message",
                "private toString output",
                IllegalArgumentException::class.java.name,
                "\tat ",
            )
    }

    @Test
    fun `records unavailable identity and cause without including the exception message`(output: CapturedOutput) {
        val failure = SnapshotContentException("private snapshot without cause")

        reporter.report(failure, SnapshotFailureOperation.FEED_LOOKUP, null)

        assertThat(output.out + output.err)
            .contains(
                "operation=FEED_LOOKUP",
                "postId=none",
                "errorType=${SnapshotContentException::class.java.name}",
                "causeType=none",
            ).doesNotContain("private snapshot without cause", "\tat ")
    }

    private class SensitiveSnapshotCause :
        IllegalStateException(
            "private cause message",
            IllegalArgumentException("private root message"),
        ) {
        override fun toString(): String = "private toString output"
    }
}
