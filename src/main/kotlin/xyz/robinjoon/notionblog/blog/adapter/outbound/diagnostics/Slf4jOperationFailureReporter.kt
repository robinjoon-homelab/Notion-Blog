package xyz.robinjoon.notionblog.blog.adapter.outbound.diagnostics

import org.slf4j.LoggerFactory
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.RecoveredOperation

// 작업 이름, 보고된 예외 타입, 가장 안쪽 원인의 타입만 로그로 남긴다. 메시지에는 서명된 파일 URL·원본 식별자·
// 응답 상세가 들어 있을 수 있고 스택 트레이스는 이를 그대로 출력하기 때문이다.
class Slf4jOperationFailureReporter : OperationFailureReporter {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun report(
        operation: RecoveredOperation,
        failure: RuntimeException,
    ) {
        logger.warn(
            "Recovered operation failure operation={} errorType={} rootCauseType={}",
            operation.name,
            failure.javaClass.name,
            rootCause(failure)?.javaClass?.name ?: "none",
        )
    }

    // 원인 사슬이 순환하면 이전에 보지 못한 마지막 원인에서 멈춘다.
    private fun rootCause(failure: Throwable): Throwable? {
        val seen = mutableSetOf(failure)
        return generateSequence(failure.cause) { it.cause }.takeWhile(seen::add).lastOrNull()
    }
}
