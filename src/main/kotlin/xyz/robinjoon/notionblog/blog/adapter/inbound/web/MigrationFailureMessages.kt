package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage

internal object MigrationFailureMessages {
    fun append(
        message: String,
        diagnostic: MigrationFailureDetail?,
    ): String {
        if (diagnostic == null) return message
        return listOfNotNull(
            message,
            "단계: ${stageNames.getValue(diagnostic.stage)}",
            "사유: ${diagnostic.reason}",
            "예외: ${diagnostic.exceptionType}",
            diagnostic.blockId?.let { "Notion 블록: $it" },
            diagnostic.httpStatus?.let { "HTTP 상태: $it" },
            diagnostic.limitBytes?.let { "크기 제한: $it 바이트" },
        ).joinToString("\n")
    }

    private val stageNames =
        mapOf(
            MigrationFailureStage.SOURCE to "Notion 조회",
            MigrationFailureStage.BODY to "본문 블록 변환",
            MigrationFailureStage.PROPERTIES to "글 속성 매핑",
            MigrationFailureStage.AUTHORED_AT to "작성일 매핑",
            MigrationFailureStage.MEDIA to "첨부 파일 보관",
            MigrationFailureStage.IMPORT to "글 가져오기",
        )
}
