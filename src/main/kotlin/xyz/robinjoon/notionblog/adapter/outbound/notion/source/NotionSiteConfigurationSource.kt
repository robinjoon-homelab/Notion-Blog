package xyz.robinjoon.notionblog.adapter.outbound.notion.source

import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionSettingsMapper
import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.domain.source.SourceId

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
