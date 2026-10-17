package xyz.robinjoon.notionblog.blog.application.port.output.diagnostics

// 작업이 오류로 끝내지 않고 의도적으로 결과값으로 바꾼 실패다.
interface OperationFailureReporter {
    fun report(
        operation: RecoveredOperation,
        failure: RuntimeException,
    )
}

enum class RecoveredOperation {
    // 레거시 파일의 누락된 사본을 받지 못했다. 누락 미디어로 계속 표시된다.
    LEGACY_MEDIA_CAPTURE,

    // 관리자 새로고침이 실패했다. 관리자는 실패 종류만 보고 글은 기존 리비전을 유지한다.
    POST_REFRESH,
}
