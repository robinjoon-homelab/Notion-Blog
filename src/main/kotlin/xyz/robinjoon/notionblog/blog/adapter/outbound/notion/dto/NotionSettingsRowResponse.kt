package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto

import tools.jackson.databind.JsonNode

internal data class NotionSettingsRowResponse(
    val id: String,
    val properties: JsonNode,
)
