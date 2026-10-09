package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedPublicationRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedSiteConfigurationRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedSyncStateRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository

@Configuration(proxyBeanMethods = false)
internal class PersistenceConfig {
    @Bean
    fun jsonBlockTreeSnapshotCodec(): JsonBlockTreeSnapshotCodec = JsonBlockTreeSnapshotCodec()

    @Bean
    fun postRepository(snapshotCodec: JsonBlockTreeSnapshotCodec): PostRepository = ExposedPostRepository(snapshotCodec)

    @Bean
    fun publicationRepository(): PublicationRepository = ExposedPublicationRepository()

    @Bean
    fun siteConfigurationRepository(): SiteConfigurationRepository = ExposedSiteConfigurationRepository()

    @Bean
    fun syncStateRepository(): SyncStateRepository = ExposedSyncStateRepository()
}
