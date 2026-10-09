package xyz.robinjoon.notionblog.adapter.outbound.notion

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException

internal data class NotionMeetingNotesSections(
    val publicBlockIds: List<String>,
    val transcriptBlockId: String?,
) {
    companion object {
        private val empty = NotionMeetingNotesSections(emptyList(), null)

        fun read(block: NotionBlockEnvelope): NotionMeetingNotesSections {
            if (block.type != "meeting_notes") return empty
            val children = block.payload.get("children") ?: return empty
            if (children.isNull) return empty
            if (!children.isObject) {
                throw SourceMappingException("Notion meeting notes children must be an object or null")
            }
            val transcriptBlockId = children.optionalBlockId("transcript_block_id")
            return NotionMeetingNotesSections(
                publicBlockIds =
                    listOfNotNull(
                        children.optionalBlockId("summary_block_id"),
                        children.optionalBlockId("notes_block_id"),
                    ).distinct(),
                transcriptBlockId = transcriptBlockId,
            )
        }

        private fun JsonNode.optionalBlockId(field: String): String? {
            val value = get(field) ?: return null
            if (value.isNull) return null
            if (!value.isString || value.stringValue().isBlank()) {
                throw SourceMappingException("Notion meeting notes $field must be a nonblank block ID or null")
            }
            return try {
                NotionIdNormalizer.normalize(value.stringValue())
            } catch (exception: SourceMappingException) {
                throw SourceMappingException("Notion meeting notes $field is malformed", exception)
            }
        }
    }
}
