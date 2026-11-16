package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import java.time.Instant

internal class NotionMigrationPropertiesMapper(
    sourceId: SourceId,
) {
    private val media = NotionMediaMapper(NotionRichTextMapper(sourceId))

    fun map(
        page: NotionPageResponse,
        fields: MigrationFields,
    ): PostProperties =
        PostProperties(
            title(page, fields),
            optionalProperty(page, fields.summaryPropertyId, setOf("rich_text"))?.let(::text)?.ifBlank { null },
            optionalProperty(page, fields.tagsPropertyId, setOf("multi_select", "select"))?.let(::tags).orEmpty(),
            page.cover?.let(media::source),
        )

    fun title(
        page: NotionPageResponse,
        fields: MigrationFields,
        fallback: String? = null,
    ): String {
        val property = property(page, fields.titlePropertyId, setOf("title", "rich_text"))
        return text(property).takeIf(String::isNotBlank)
            ?: fallback
            ?: throw SourceMappingException("Mapped migration title is empty")
    }

    fun authoredAt(
        page: NotionPageResponse,
        fields: MigrationFields,
    ): Instant? = optionalProperty(page, fields.authoredAtPropertyId, setOf("date", "created_time"))?.let(NotionMigrationDates::read)

    private fun optionalProperty(
        page: NotionPageResponse,
        id: String,
        types: Set<String>,
    ): JsonNode? = if (id.isBlank()) null else property(page, id, types)

    private fun property(
        page: NotionPageResponse,
        id: String,
        types: Set<String>,
    ): JsonNode {
        val property =
            page.properties.values().singleOrNull { NotionPropertyIds.same(it.get("id")?.stringValue().orEmpty(), id) }
                ?: throw SourceConfigurationException("Mapped migration property is missing")
        if (property.optionalText("type") !in
            types
        ) {
            throw SourceConfigurationException("Mapped migration property has an incompatible type")
        }
        return property
    }

    private fun text(property: JsonNode): String {
        val type = property.requiredText("type")
        return property
            .requiredArray(type)
            .joinToString("") { part ->
                part.get("plain_text")?.takeIf(JsonNode::isString)?.stringValue()
                    ?: throw SourceMappingException("Mapped migration text is malformed")
            }.trim()
    }

    private fun tags(property: JsonNode): List<String> {
        val options =
            when (property.requiredText("type")) {
                "multi_select" -> property.requiredArray("multi_select")
                else -> listOfNotNull(property.optionalObject("select"))
            }
        return options.map { it.requiredText("name").trim() }.filter(String::isNotBlank).distinct()
    }
}
