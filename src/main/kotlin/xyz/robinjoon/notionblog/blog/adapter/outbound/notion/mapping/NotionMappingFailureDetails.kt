package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import java.util.UUID

// 허용 목록의 앱 문구만 진단으로 바꾼다. 알 수 없는 메시지와 원인 문자열은 화면에 전달하지 않는다.
internal object NotionMappingFailureDetails {
    fun of(
        exception: IllegalArgumentException,
        stage: MigrationFailureStage,
        blockId: String? = null,
    ): MigrationFailureDetail =
        MigrationFailureDetail(
            stage,
            reasons[exception.message] ?: "데이터 형식이 블로그의 가져오기 조건과 맞지 않습니다.",
            if (exception is NotionBlockMappingException) "NotionBlockMappingException" else "IllegalArgumentException",
            validatedBlockId(blockId),
        )

    fun reference(blockId: String): MigrationFailureDetail =
        MigrationFailureDetail(
            MigrationFailureStage.BODY,
            "블록이 참조하는 Notion 식별자 형식이 올바르지 않습니다.",
            "SourceMappingException",
            validatedBlockId(blockId),
        )

    fun validatedBlockId(value: String?): UUID? {
        val normalized = value?.let(NotionIdNormalizer::normalizeOrNull) ?: return null
        return UUID(normalized.substring(0, 16).toULong(16).toLong(), normalized.substring(16).toULong(16).toLong())
    }

    private val reasons =
        mapOf(
            "URL is invalid" to "주소 형식이 올바르지 않습니다.",
            "URL must use http or https" to "http 또는 https 주소만 지원합니다.",
            "rich text type is unsupported" to "지원하지 않는 리치 텍스트 형식입니다.",
            "rich text must be an array" to "리치 텍스트가 배열 형식이 아닙니다.",
            "color is unsupported" to "지원하지 않는 색상 값입니다.",
            "media source is unsupported" to "지원하지 않는 첨부 파일 원본 형식입니다.",
            "numbered list format is unsupported" to "지원하지 않는 번호 목록 형식입니다.",
            "native icon color must be a foreground color" to "아이콘의 색상 값이 올바르지 않습니다.",
            "text content must be a string" to "텍스트 내용이 문자열 형식이 아닙니다.",
            "required rich_text array is missing" to "리치 텍스트 배열이 없습니다.",
            "required block_type is missing" to "미지원 블록의 형식 정보가 없습니다.",
            "list_start_index must be a positive integer" to "번호 목록의 list_start_index가 양의 정수가 아닙니다.",
            "link_to_page type is unsupported" to "지원하지 않는 페이지 링크 형식입니다.",
            "required is_toggleable is missing" to "제목 블록의 is_toggleable 값이 없거나 논리값이 아닙니다.",
            "required url is missing" to "주소의 url 값이 없거나 빈 문자열입니다.",
            "media expiry is invalid" to "첨부 파일의 expiry_time 형식이 올바르지 않습니다.",
            "required file object is missing" to "첨부 파일의 file 정보가 없습니다.",
            "required external object is missing" to "외부 첨부 파일의 external 정보가 없습니다.",
        )
}
