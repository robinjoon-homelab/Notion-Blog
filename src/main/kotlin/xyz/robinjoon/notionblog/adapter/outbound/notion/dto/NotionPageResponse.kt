package xyz.robinjoon.notionblog.adapter.outbound.notion.dto

import tools.jackson.databind.JsonNode

internal data class NotionPageResponse
    // quality-exception: Notion page 응답의 게시 상태, 부모, 리비전, 콘텐츠와 미디어 필드 9개를 보존한다.
    @Suppress("LongParameterList")
    constructor(
        val id: String,
        val parent: NotionPageParentResponse,
        val url: String,
        val publicUrl: String?,
        val inTrash: Boolean,
        val lastEditedTime: String,
        val properties: JsonNode,
        val icon: JsonNode? = null,
        val cover: JsonNode? = null,
    )

internal data class NotionPageParentResponse(
    val type: String,
    val pageId: String?,
    val dataSourceId: String? = null,
)
