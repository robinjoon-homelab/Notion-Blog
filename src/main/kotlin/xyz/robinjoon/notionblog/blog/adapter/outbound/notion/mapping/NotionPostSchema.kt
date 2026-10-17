package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPostsConfiguration
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException

/** 글 데이터 소스의 스키마를 설정된 매핑과 대조한 뒤 확정한 속성 ID. */
internal data class NotionPostSchema(
    val titlePropertyId: String,
) {
    companion object {
        fun validate(
            dataSource: NotionDataSourceResponse,
            posts: NotionPostsConfiguration,
        ): NotionPostSchema {
            if (dataSource.inTrash) throw SourceAccessException("Notion posts data source is in the trash")
            val titles = dataSource.properties.filter { it.type == "title" }
            val title = titles.singleOrNull() ?: throw SourceConfigurationException("Notion posts data source must have one title property")
            requireProperty(dataSource, posts.summaryPropertyId, "rich_text")
            requireProperty(dataSource, posts.tagsPropertyId, "multi_select")
            return NotionPostSchema(title.id)
        }

        private fun requireProperty(
            dataSource: NotionDataSourceResponse,
            configuredId: String,
            type: String,
        ): NotionDatabaseProperty? {
            if (configuredId.isBlank()) return null
            val property =
                dataSource.properties.firstOrNull { NotionPropertyIds.same(it.id, configuredId) }
                    ?: throw SourceConfigurationException("Notion posts data source is missing a configured property")
            if (property.type != type) throw SourceConfigurationException("Notion posts data source property has an unexpected type")
            return property
        }
    }
}
