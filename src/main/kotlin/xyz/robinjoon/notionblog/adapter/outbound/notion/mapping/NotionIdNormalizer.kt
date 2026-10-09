package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException

internal object NotionIdNormalizer {
    fun normalize(value: String): String = normalizeOrNull(value) ?: throw SourceMappingException("Notion page ID is malformed")

    fun normalizeOrNull(value: String): String? {
        val trimmed = value.trim()
        val compact =
            when {
                undashedPageId.matches(trimmed) -> trimmed
                dashedPageId.matches(trimmed) -> trimmed.replace("-", "")
                else -> return null
            }
        return compact.lowercase()
    }

    private val undashedPageId = Regex("[0-9a-fA-F]{32}")
    private val dashedPageId =
        Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
}
