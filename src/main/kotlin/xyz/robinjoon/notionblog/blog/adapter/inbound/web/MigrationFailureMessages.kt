package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

internal object MigrationFailureMessages {
    fun append(
        message: String,
        diagnostic: MigrationFailureDetail?,
    ): String = if (diagnostic == null) message else "$message\n${details(diagnostic)}"

    fun result(failure: MigratePostResult.Failed): String {
        val message =
            if (failure.diagnostic?.stage == MigrationFailureStage.IMPORT) {
                "글을 초안으로 저장하지 못했습니다."
            } else {
                failureMessages.getValue(failure.kind)
            }
        return append(message, failure.diagnostic)
    }

    fun partial(warnings: List<MigrationFailureDetail>): String =
        warnings.joinToString(
            separator = "\n\n",
            prefix = "일부 가져옴 · 확인할 항목 ${warnings.size}개. 미리보기에서 초안을 확인하세요.\n\n",
            transform = ::details,
        )

    private fun details(diagnostic: MigrationFailureDetail): String =
        listOfNotNull(
            "단계: ${stageNames.getValue(diagnostic.stage)}",
            "사유: ${diagnostic.reason}",
            "예외: ${diagnostic.exceptionType}",
            diagnostic.blockId?.let { "Notion 블록: $it" },
            diagnostic.httpStatus?.let { "HTTP 상태: $it" },
            diagnostic.limitBytes?.let { "크기 제한: $it 바이트" },
        ).joinToString("\n")

    private val stageNames =
        mapOf(
            MigrationFailureStage.SOURCE to "Notion 조회",
            MigrationFailureStage.BODY to "본문 블록 변환",
            MigrationFailureStage.PROPERTIES to "글 속성 매핑",
            MigrationFailureStage.AUTHORED_AT to "작성일 매핑",
            MigrationFailureStage.MEDIA to "첨부 파일 보관",
            MigrationFailureStage.IMPORT to "글 가져오기",
        )

    private val failureMessages =
        mapOf(
            SyncFailureKind.RETRYABLE_SOURCE to "Notion 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.",
            SyncFailureKind.AUTHENTICATION to "서버의 Notion 연동 인증을 확인하세요.",
            SyncFailureKind.ACCESS to "원본 DB와 본문에서 참조한 페이지의 읽기 권한을 확인하세요.",
            SyncFailureKind.CONFIGURATION to "선택한 데이터 소스와 필드 매핑을 확인하세요.",
            SyncFailureKind.MAPPING to "본문 또는 글 속성을 변환하지 못했습니다.",
            SyncFailureKind.MEDIA to "첨부 파일을 보관하지 못했습니다. 원본 파일과 크기를 확인한 뒤 다시 시도하세요.",
        )
}
