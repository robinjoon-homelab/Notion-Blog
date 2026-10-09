package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.application.port.input.GetBlogPageUseCase
import xyz.robinjoon.notionblog.application.port.input.GetPostFeedUseCase
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.application.service.GetBlogPageService
import xyz.robinjoon.notionblog.application.service.GetPostFeedService
import xyz.robinjoon.notionblog.application.service.GetPublishedPostService
import xyz.robinjoon.notionblog.application.service.ResolvePostLinksService
import xyz.robinjoon.notionblog.application.service.SynchronizationQueryService

@Configuration(proxyBeanMethods = false)
internal class QueryConfig(
    private val posts: PostRepository,
    private val publications: PublicationRepository,
    private val siteConfigurations: SiteConfigurationRepository,
    private val presentationAssets: PresentationAssetCatalog,
    private val snapshotFailures: SnapshotFailureReporter,
) {
    @Bean
    fun getPublishedPostService(): GetPublishedPostService = GetPublishedPostService(publications, posts, snapshotFailures)

    @Bean
    fun resolvePostLinksService(): ResolvePostLinksService = ResolvePostLinksService(posts, publications)

    @Bean
    fun getBlogPageUseCase(
        publishedPosts: GetPublishedPostService,
        links: ResolvePostLinksService,
    ): GetBlogPageUseCase = GetBlogPageService(publishedPosts, posts, siteConfigurations, presentationAssets, links)

    @Bean
    fun getPostFeedUseCase(): GetPostFeedUseCase = GetPostFeedService(posts, publications, siteConfigurations, snapshotFailures)

    @Bean
    fun synchronizationQueryService(states: SyncStateRepository): SynchronizationQueryService =
        SynchronizationQueryService(siteConfigurations, publications, posts, states)
}
