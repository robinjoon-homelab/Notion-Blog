package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPostsConfiguration
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties

/**
 * 페이지에서 요약, 태그, 커버를 읽는다. 요약과 태그는 글 데이터 소스의 직접 행에만 적용하고,
 * 그 밖의 페이지(레거시 문서)는 페이지 커버만 유지한다.
 */
internal class NotionPostPropertiesMapper(
    sourceId: SourceId,
    private val posts: NotionPostsConfiguration,
) {
    private val media = NotionMediaMapper(NotionRichTextMapper(sourceId))

    fun map(
        page: NotionPageResponse,
        title: String,
    ): PostProperties {
        val member = isPostRow(page)
        return PostProperties(
            title = title,
            summary = if (member) summary(page.properties) else null,
            tags = if (member) tags(page.properties) else emptyList(),
            cover = page.cover?.let(media::source),
        )
    }

    private fun isPostRow(page: NotionPageResponse): Boolean =
        page.parent.type == "data_source_id" &&
            page.parent.dataSourceId?.let(NotionIdNormalizer::normalizeOrNull) == NotionIdNormalizer.normalizeOrNull(posts.dataSourceId)

    private fun summary(properties: JsonNode): String? = plainText(properties, posts.summaryPropertyId)

    private fun tags(properties: JsonNode): List<String> {
        val property = property(properties, posts.tagsPropertyId, "multi_select") ?: return emptyList()
        return property
            .path("multi_select")
            .values()
            .map { option -> option.requiredText("name").trim() }
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun plainText(
        properties: JsonNode,
        propertyId: String,
    ): String? {
        val property = property(properties, propertyId, "rich_text") ?: return null
        return property
            .path("rich_text")
            .values()
            .joinToString("") { value ->
                value
                    .get("plain_text")
                    ?.takeIf(JsonNode::isString)
                    ?.stringValue()
                    .orEmpty()
            }.replace(whitespace, " ")
            .trim()
            .ifBlank { null }
    }

    private fun property(
        properties: JsonNode,
        propertyId: String,
        type: String,
    ): JsonNode? {
        if (propertyId.isBlank()) return null
        val matches = properties.values().filter { NotionPropertyIds.same(it.get("id")?.stringValue().orEmpty(), propertyId) }
        val property = matches.singleOrNull() ?: return null
        if (property.optionalText("type") != type) throw SourceConfigurationException("Notion post property has an unexpected type")
        return property
    }

    private companion object {
        val whitespace = Regex("\\s+")
    }
}
