package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

internal class NotionBlockMappingException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
