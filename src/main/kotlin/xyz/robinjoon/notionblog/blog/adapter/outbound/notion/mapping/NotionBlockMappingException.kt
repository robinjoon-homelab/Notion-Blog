package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

internal class NotionBlockMappingException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
