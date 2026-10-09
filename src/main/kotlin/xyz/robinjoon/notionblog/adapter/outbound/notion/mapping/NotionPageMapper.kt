package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.time.Instant
import java.time.format.DateTimeParseException

internal class NotionPageMapper {
    fun map(
        page: NotionPageResponse,
        requestedReference: SourceDocumentRef,
    ): NotionPageMetadata =
        try {
            val sourceDocument = requestedReference.copy(externalId = NotionIdNormalizer.normalize(requestedReference.externalId))
            if (NotionIdNormalizer.normalize(page.id) != sourceDocument.externalId) {
                throw SourceMappingException("Notion page did not match the requested reference")
            }
            NotionPageMetadata(
                sourceDocument = sourceDocument,
                title = title(page.properties),
                publicationStatus =
                    if (page.inTrash || page.publicUrl == null) {
                        ImportedPublicationStatus.UNPUBLISHED
                    } else {
                        ImportedPublicationStatus.PUBLISHED
                    },
                sourceRevision = SourceRevision(Instant.parse(page.lastEditedTime.trim()).toString()),
            )
        } catch (exception: SourceMappingException) {
            throw exception
        } catch (exception: DateTimeParseException) {
            throw SourceMappingException("Notion page revision is malformed", exception)
        } catch (exception: IllegalArgumentException) {
            throw SourceMappingException("Notion page metadata is malformed", exception)
        }

    private fun title(properties: JsonNode): String {
        val titleProperties =
            properties
                .properties()
                .asSequence()
                .map { it.value }
                .filter { property -> property.get("type")?.takeIf(JsonNode::isString)?.stringValue() == "title" }
                .toList()
        if (titleProperties.size != 1) {
            throw SourceMappingException("Notion page must contain one title property")
        }
        val values =
            titleProperties
                .single()
                .get("title")
                ?.takeIf(JsonNode::isArray)
                ?.values()
                ?.toList()
                ?: throw SourceMappingException("Notion page title is malformed")
        return values
            .joinToString(separator = "") { value ->
                value.get("plain_text")?.takeIf(JsonNode::isString)?.stringValue()
                    ?: throw SourceMappingException("Notion page title is malformed")
            }.replace(whitespace, " ")
            .trim()
            .ifBlank { UNTITLED_TITLE }
    }

    private companion object {
        val whitespace = Regex("\\s+")
        const val UNTITLED_TITLE = "Untitled"
    }
}

internal data class NotionPageMetadata(
    val sourceDocument: SourceDocumentRef,
    val title: String,
    val publicationStatus: ImportedPublicationStatus,
    val sourceRevision: SourceRevision,
)
