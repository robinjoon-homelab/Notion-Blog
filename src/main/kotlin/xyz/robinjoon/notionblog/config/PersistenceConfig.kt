package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedLegacyPostStore
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedMediaStorage
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPresentationProfileRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublicationRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedPostStore
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedRevisionMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedSiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedSyncStateRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.LegacyPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository

@Configuration(proxyBeanMethods = false)
internal class PersistenceConfig {
    @Bean
    fun jsonBlockTreeSnapshotCodec(): JsonBlockTreeSnapshotCodec = JsonBlockTreeSnapshotCodec()

    @Bean
    fun jsonPostPropertiesCodec(): JsonPostPropertiesCodec = JsonPostPropertiesCodec()

    @Bean
    fun exposedRevisionMapper(
        snapshotCodec: JsonBlockTreeSnapshotCodec,
        propertiesCodec: JsonPostPropertiesCodec,
    ): ExposedRevisionMapper = ExposedRevisionMapper(snapshotCodec, propertiesCodec)

    @Bean
    fun postRepository(revisions: ExposedRevisionMapper): PostRepository = ExposedPostRepository(revisions)

    @Bean
    fun publishedPostStore(
        revisions: ExposedRevisionMapper,
        propertiesCodec: JsonPostPropertiesCodec,
    ): PublishedPostStore = ExposedPublishedPostStore(revisions, propertiesCodec)

    @Bean
    fun mediaStorage(): MediaStoragePort = ExposedMediaStorage()

    @Bean
    fun legacyPostStore(snapshotCodec: JsonBlockTreeSnapshotCodec): LegacyPostStore = ExposedLegacyPostStore(snapshotCodec)

    @Bean
    fun publicationRepository(): PublicationRepository = ExposedPublicationRepository()

    @Bean
    fun presentationProfileRepository(): PresentationProfileRepository = ExposedPresentationProfileRepository()

    @Bean
    fun siteConfigurationRepository(): SiteConfigurationRepository = ExposedSiteConfigurationRepository()

    @Bean
    fun syncStateRepository(): SyncStateRepository = ExposedSyncStateRepository()
}
