package xyz.robinjoon.notionblog.adapter.outbound.notion.dto

internal sealed interface NotionParentResponse {
    data class Page(
        val pageId: String,
    ) : NotionParentResponse

    data class Block(
        val blockId: String,
    ) : NotionParentResponse

    data class Database(
        val databaseId: String,
    ) : NotionParentResponse

    data class DataSource(
        val dataSourceId: String,
    ) : NotionParentResponse

    data object Workspace : NotionParentResponse

    data class Unsupported(
        val type: String,
    ) : NotionParentResponse
}
