package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostCatalog
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionRevisionSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionSiteConfigurationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostCatalog
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
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
    ): NotionPostSource =
        NotionPostSource(
            sourceId = SourceId(properties.sourceId),
            client = client,
            maxDepth = properties.maxBlockDepth,
            maxBlockCount = properties.maxBlockCount,
            collectionTimeout = properties.collectionTimeout,
        )

    @Bean
    fun postRevisionSource(
        properties: NotionProperties,
        source: NotionPostSource,
    ): PostRevisionSource =
        NotionRevisionSource(
            SourceId(properties.sourceId),
            source,
            properties.posts.dataSourceId,
            properties.posts.summaryPropertyId,
            properties.posts.tagsPropertyId,
        )

    @Bean
    fun postCatalog(
        properties: NotionProperties,
        client: NotionApiClient,
    ): PostCatalog =
        NotionPostCatalog(
            SourceId(properties.sourceId),
            client,
            properties.posts.dataSourceId,
            properties.posts.summaryPropertyId,
            properties.posts.tagsPropertyId,
        )

    @Bean
    fun siteConfigurationSource(
        properties: NotionProperties,
        client: NotionApiClient,
    ): SiteConfigurationSource = NotionSiteConfigurationSource(SourceId(properties.sourceId), properties.settingsDataSourceId, client)
}
