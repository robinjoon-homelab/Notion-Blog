package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionSiteConfigurationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.blog.domain.SourceId

@Configuration(proxyBeanMethods = false)
internal class NotionConfig {
    @Bean
    fun notionApiClient(properties: NotionProperties): NotionApiClient =
        NotionApiClient(
            baseUrl = properties.baseUrl,
            token = properties.token,
            requestTimeout = properties.requestTimeout,
            collectionTimeout = properties.collectionTimeout,
        )

    @Bean
    fun postSource(
        properties: NotionProperties,
        client: NotionApiClient,
    ): PostSource =
        NotionPostSource(
            sourceId = SourceId(properties.sourceId),
            client = client,
            maxDepth = properties.maxBlockDepth,
            maxBlockCount = properties.maxBlockCount,
            collectionTimeout = properties.collectionTimeout,
        )

    @Bean
    fun siteConfigurationSource(
        properties: NotionProperties,
        client: NotionApiClient,
    ): SiteConfigurationSource = NotionSiteConfigurationSource(SourceId(properties.sourceId), properties.settingsDataSourceId, client)
}
