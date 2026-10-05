package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.adapter.input.web.PostPageViewAssembler
import xyz.robinjoon.notionblog.adapter.input.web.PublicBlogUrls
import xyz.robinjoon.notionblog.adapter.input.web.RssFeedRenderer
import xyz.robinjoon.notionblog.adapter.input.web.RssSummaryExtractor
import xyz.robinjoon.notionblog.adapter.output.notion.NotionPostSource
import xyz.robinjoon.notionblog.adapter.output.notion.NotionSiteConfigurationSource
import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.ExposedPublicationRepository
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.ExposedSiteConfigurationRepository
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.ExposedSyncStateRepository
import xyz.robinjoon.notionblog.adapter.output.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.adapter.output.presentation.ClasspathPresentationAssetCatalog
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.application.service.ApplyImportedSiteConfigurationService
import xyz.robinjoon.notionblog.application.service.GetPublishedPostService
import xyz.robinjoon.notionblog.application.service.ResolvePostLinksService
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import java.time.Clock
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotionProperties::class, BlogProperties::class)
internal class ApplicationConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun refreshPolicy(properties: BlogProperties): RefreshPolicy = RefreshPolicy(
        successInterval = properties.synchronization.successInterval,
        initialFailureDelay = properties.synchronization.initialFailureDelay,
        maximumFailureDelay = properties.synchronization.maximumFailureDelay,
    )

    @Bean
    fun jsonBlockTreeSnapshotCodec(): JsonBlockTreeSnapshotCodec = JsonBlockTreeSnapshotCodec()

    @Bean
    fun postRepository(snapshotCodec: JsonBlockTreeSnapshotCodec): ExposedPostRepository = ExposedPostRepository(snapshotCodec)

    @Bean
    fun publicationRepository(): ExposedPublicationRepository = ExposedPublicationRepository()

    @Bean
    fun siteConfigurationRepository(): ExposedSiteConfigurationRepository = ExposedSiteConfigurationRepository()

    @Bean
    fun syncStateRepository(): ExposedSyncStateRepository = ExposedSyncStateRepository()

    @Bean
    internal fun notionApiClient(properties: NotionProperties): NotionApiClient = NotionApiClient(
        baseUrl = properties.baseUrl,
        token = properties.token,
        requestTimeout = properties.requestTimeout,
        collectionTimeout = properties.collectionTimeout,
    )

    @Bean
    internal fun postSource(properties: NotionProperties, client: NotionApiClient): NotionPostSource = NotionPostSource(
        sourceId = SourceId(properties.sourceId),
        client = client,
        maxDepth = properties.maxBlockDepth,
        maxBlockCount = properties.maxBlockCount,
        collectionTimeout = properties.collectionTimeout,
    )

    @Bean
    internal fun siteConfigurationSource(properties: NotionProperties, client: NotionApiClient): NotionSiteConfigurationSource = NotionSiteConfigurationSource(
        sourceId = SourceId(properties.sourceId),
        settingsDataSourceId = properties.settingsDataSourceId,
        client = client,
    )

    @Bean
    fun presentationAssetCatalog(properties: BlogProperties): ClasspathPresentationAssetCatalog {
        val references = properties.presentation.assets.associate { asset ->
            val reference = PresentationAssetRef(asset.key, asset.version, asset.integrity)
            reference to PresentationAssetDescriptor(asset.publicPath, asset.mediaType, asset.integrity)
        }
        val currentReferences = properties.presentation.assets
            .filter(BlogProperties.Asset::current)
            .associate { asset ->
                asset.key to PresentationAssetRef(asset.key, asset.version, asset.integrity)
            }
        return ClasspathPresentationAssetCatalog(references, currentReferences)
    }

    @Bean
    fun getPublishedPostService(
        publications: PublicationRepository,
        posts: PostRepository,
    ): GetPublishedPostService = GetPublishedPostService(publications, posts)

    @Bean
    fun resolvePostLinksService(
        posts: PostRepository,
        publications: PublicationRepository,
    ): ResolvePostLinksService = ResolvePostLinksService(posts, publications)

    @Bean
    fun publicBlogUrls(properties: BlogProperties): PublicBlogUrls = PublicBlogUrls(properties.publicBaseUri)

    @Bean
    fun rssSummaryExtractor(): RssSummaryExtractor = RssSummaryExtractor()

    @Bean
    fun rssFeedRenderer(summaryExtractor: RssSummaryExtractor): RssFeedRenderer = RssFeedRenderer(summaryExtractor)

    @Bean
    fun postPageViewAssembler(clock: Clock, urls: PublicBlogUrls): PostPageViewAssembler = PostPageViewAssembler(clock, urls.feedUrl)

    @Bean
    fun postIdFactory(): (SourceDocumentRef) -> PostId = { PostId(UUID.randomUUID()) }

    @Bean
    fun publicationIdFactory(): () -> PublicationId = { PublicationId(UUID.randomUUID()) }

    @Bean
    fun revisionIdFactory(): () -> PublicationRevisionId = { PublicationRevisionId(UUID.randomUUID()) }

    @Bean
    fun defaultPresentationProfileKey(properties: BlogProperties): PresentationProfileKey = PresentationProfileKey(properties.presentation.defaultProfileKey)

    @Bean
    fun applyImportedSiteConfigurationService(
        sites: SiteConfigurationRepository,
        publications: PublicationRepository,
        assets: PresentationAssetCatalog,
        states: SyncStateRepository,
        clock: Clock,
        refreshPolicy: RefreshPolicy,
        defaultProfileKey: PresentationProfileKey,
        publicationIdFactory: () -> PublicationId,
    ): ApplyImportedSiteConfigurationService = ApplyImportedSiteConfigurationService(
        siteConfigurationRepository = sites,
        publicationRepository = publications,
        presentationAssetCatalog = assets,
        syncStateRepository = states,
        clock = clock,
        refreshPolicy = refreshPolicy,
        defaultPresentationProfileKey = defaultProfileKey,
        publicationIdFactory = publicationIdFactory,
    )
}
