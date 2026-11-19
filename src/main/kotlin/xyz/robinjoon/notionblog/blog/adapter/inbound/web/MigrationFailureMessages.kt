package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage

internal object MigrationFailureMessages {
    fun append(
        message: String,
        diagnostic: MigrationFailureDetail?,
    ): String = if (diagnostic == null) message else "$message\n${details(diagnostic)}"

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
}
