package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto

/**
 * 직접 행이 블로그 글인 단일 노션 데이터 소스를 가리킨다.
 * 속성 ID가 비어 있으면 선택 속성을 매핑하지 않은 것이며, 제목 속성은 타입으로 찾는다.
 */
internal data class NotionPostsConfiguration(
    val dataSourceId: String,
    val summaryPropertyId: String = "",
    val tagsPropertyId: String = "",
)
