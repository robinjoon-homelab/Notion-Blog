package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
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
            ?: throw SourceMappingException(
                "Mapped migration title is empty",
                diagnostic = detail("매핑한 제목이 비어 있습니다.", "SourceMappingException"),
            )
    }

    fun authoredAt(
        page: NotionPageResponse,
        fields: MigrationFields,
    ): Instant? =
        optionalProperty(
            page,
            fields.authoredAtPropertyId,
            setOf("date", "created_time"),
            MigrationFailureStage.AUTHORED_AT,
        )?.let(NotionMigrationDates::read)

    private fun optionalProperty(
        page: NotionPageResponse,
        id: String,
        types: Set<String>,
        stage: MigrationFailureStage = MigrationFailureStage.PROPERTIES,
    ): JsonNode? = if (id.isBlank()) null else property(page, id, types, stage)

    private fun property(
        page: NotionPageResponse,
        id: String,
        types: Set<String>,
        stage: MigrationFailureStage = MigrationFailureStage.PROPERTIES,
    ): JsonNode {
        val property =
            page.properties.values().singleOrNull { NotionPropertyIds.same(it.get("id")?.stringValue().orEmpty(), id) }
                ?: throw SourceConfigurationException(
                    "Mapped migration property is missing",
                    diagnostic = detail("매핑한 속성이 원본 글에 없습니다.", "SourceConfigurationException", stage),
                )
        if (property.optionalText("type") !in
            types
        ) {
            throw SourceConfigurationException(
                "Mapped migration property has an incompatible type",
                diagnostic = detail("매핑한 속성의 타입이 선택한 용도와 맞지 않습니다.", "SourceConfigurationException", stage),
            )
        }
        return property
    }

    private fun text(property: JsonNode): String {
        val type = property.requiredText("type")
        return property
            .requiredArray(type)
            .joinToString("") { part ->
                part.get("plain_text")?.takeIf(JsonNode::isString)?.stringValue()
                    ?: throw SourceMappingException(
                        "Mapped migration text is malformed",
                        diagnostic = detail("매핑한 텍스트 속성의 plain_text가 문자열 형식이 아닙니다.", "SourceMappingException"),
                    )
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

    private fun detail(
        reason: String,
        type: String,
        stage: MigrationFailureStage = MigrationFailureStage.PROPERTIES,
    ): MigrationFailureDetail = MigrationFailureDetail(stage, reason, type)
}
