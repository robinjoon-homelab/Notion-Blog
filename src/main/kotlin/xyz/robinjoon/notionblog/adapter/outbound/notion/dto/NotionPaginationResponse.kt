package xyz.robinjoon.notionblog.adapter.outbound.notion.dto

internal data class NotionPaginationResponse<T>(
    val results: List<T>,
    val hasMore: Boolean,
    val nextCursor: String?,
) {
    init {
        require((hasMore && !nextCursor.isNullOrBlank()) || (!hasMore && nextCursor == null)) {
            "Notion pagination fields are inconsistent"
        }
    }
}
