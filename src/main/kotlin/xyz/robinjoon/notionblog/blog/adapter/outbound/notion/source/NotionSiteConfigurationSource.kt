package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionSettingsMapper
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.blog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.blog.domain.SourceId

internal class NotionSiteConfigurationSource(
    sourceId: SourceId,
    private val settingsDataSourceId: String,
    private val client: NotionApiClient,
) : SiteConfigurationSource {
    private val mapper = NotionSettingsMapper(sourceId)

    init {
        require(settingsDataSourceId.isNotBlank()) { "Notion settings data source ID must not be blank" }
    }

    override fun fetch(): ImportedSiteConfiguration = mapper.map(client.fetchSettingsRows(settingsDataSourceId))
}
