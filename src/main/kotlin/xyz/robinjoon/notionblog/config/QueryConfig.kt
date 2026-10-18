package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.application.port.input.AdminPostQueryUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetMediaUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicFeedUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicSeriesUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedSeriesStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostCatalog
import xyz.robinjoon.notionblog.blog.application.service.AdminPostQueryService
import xyz.robinjoon.notionblog.blog.application.service.AdminPostSummaryService
import xyz.robinjoon.notionblog.blog.application.service.ContentReferencesService
import xyz.robinjoon.notionblog.blog.application.service.GetMediaService
import xyz.robinjoon.notionblog.blog.application.service.GetPostListService
import xyz.robinjoon.notionblog.blog.application.service.GetPublicFeedService
import xyz.robinjoon.notionblog.blog.application.service.GetPublicPostService
import xyz.robinjoon.notionblog.blog.application.service.GetPublicSeriesService
import xyz.robinjoon.notionblog.blog.application.service.ResolvePostLinksService
import xyz.robinjoon.notionblog.blog.application.service.SitePresentationContextService
import xyz.robinjoon.notionblog.blog.application.service.SynchronizationQueryService

// 읽기 전용 서비스다. 공개용 서비스는 애플리케이션의 게시 상태만 읽는다.
@Configuration(proxyBeanMethods = false)
internal class QueryConfig(
    private val posts: PostRepository,
    private val publishedPosts: PublishedPostStore,
    private val siteConfigurations: SiteConfigurationRepository,
    private val snapshotFailures: SnapshotFailureReporter,
) {
    @Bean
    fun sitePresentationContextService(
        profiles: PresentationProfileRepository,
        assets: PresentationAssetCatalog,
    ): SitePresentationContextService =
        SitePresentationContextService(siteConfigurations, profiles, assets, posts, publishedPosts, snapshotFailures)

    @Bean
    fun resolvePostLinksService(): ResolvePostLinksService = ResolvePostLinksService(posts, publishedPosts)

    @Bean
    fun contentReferencesService(links: ResolvePostLinksService): ContentReferencesService = ContentReferencesService(links, posts)

    @Bean
    fun getPostListUseCase(
        chromes: SitePresentationContextService,
        references: ContentReferencesService,
        homeConfigurations: HomeConfigurationRepository,
        categories: CategoryRepository,
    ): GetPostListUseCase = GetPostListService(chromes, references, publishedPosts, snapshotFailures, homeConfigurations, categories)

    @Bean
    fun getPublicPostUseCase(
        chromes: SitePresentationContextService,
        references: ContentReferencesService,
        publishedSeries: PublishedSeriesStore,
    ): GetPublicPostUseCase = GetPublicPostService(chromes, references, publishedPosts, publishedSeries, snapshotFailures)

    @Bean
    fun getPublicSeriesUseCase(
        chromes: SitePresentationContextService,
        references: ContentReferencesService,
        publishedSeries: PublishedSeriesStore,
        homeConfigurations: HomeConfigurationRepository,
    ): GetPublicSeriesUseCase = GetPublicSeriesService(chromes, references, publishedSeries, homeConfigurations, snapshotFailures)

    @Bean
    fun getPublicFeedUseCase(chromes: SitePresentationContextService): GetPublicFeedUseCase =
        GetPublicFeedService(siteConfigurations, chromes, publishedPosts, snapshotFailures)

    @Bean
    fun getMediaUseCase(mediaStorage: MediaStoragePort): GetMediaUseCase = GetMediaService(mediaStorage)

    @Bean
    fun adminPostSummaryService(
        states: SyncStateRepository,
        catalog: PostCatalog,
        categories: CategoryRepository,
        series: SeriesRepository,
    ): AdminPostSummaryService = AdminPostSummaryService(posts, states, catalog, categories, series)

    @Bean
    fun adminPostQueryUseCase(
        summaries: AdminPostSummaryService,
        chromes: SitePresentationContextService,
        references: ContentReferencesService,
    ): AdminPostQueryUseCase = AdminPostQueryService(summaries, posts, chromes, references, snapshotFailures)

    @Bean
    fun synchronizationQueryService(states: SyncStateRepository): SynchronizationQueryService = SynchronizationQueryService(posts, states)
}
