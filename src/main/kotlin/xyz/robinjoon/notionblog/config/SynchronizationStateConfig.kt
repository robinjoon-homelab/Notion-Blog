package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.blog.application.service.ActivatePublicationService
import xyz.robinjoon.notionblog.blog.application.service.ApplyImportedPostService
import xyz.robinjoon.notionblog.blog.application.service.ApplyImportedSiteConfigurationService
import xyz.robinjoon.notionblog.blog.application.service.CancelInactivePostSynchronizationService
import xyz.robinjoon.notionblog.blog.application.service.ImportedSiteConfigurationResolver
import xyz.robinjoon.notionblog.blog.application.service.StagePublicationMemberService
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class SynchronizationStateConfig(
    private val posts: PostRepository,
    private val publications: PublicationRepository,
    private val siteConfigurations: SiteConfigurationRepository,
    private val states: SyncStateRepository,
    private val clock: Clock,
    private val refreshPolicy: RefreshPolicy,
) {
    @Bean
    fun applyImportedPostService(
        postIdFactory: (SourceDocumentRef) -> PostId,
        snapshotFailures: SnapshotFailureReporter,
    ): ApplyImportedPostService = ApplyImportedPostService(posts, states, clock, refreshPolicy, snapshotFailures, postIdFactory)

    @Bean
    fun stagePublicationMemberService(revisionIdFactory: () -> PublicationRevisionId): StagePublicationMemberService =
        StagePublicationMemberService(publications, states, clock, refreshPolicy, revisionIdFactory)

    @Bean
    fun activatePublicationService(): ActivatePublicationService =
        ActivatePublicationService(publications, posts, states, clock, refreshPolicy)

    @Bean
    fun cancelInactivePostSynchronizationService(): CancelInactivePostSynchronizationService =
        CancelInactivePostSynchronizationService(publications, states)

    @Bean
    fun importedSiteConfigurationResolver(
        profiles: PresentationProfileRepository,
        assets: PresentationAssetCatalog,
        defaultProfileKey: PresentationProfileKey,
        publicationIdFactory: () -> PublicationId,
    ): ImportedSiteConfigurationResolver = ImportedSiteConfigurationResolver(profiles, assets, defaultProfileKey, publicationIdFactory)

    @Bean
    fun applyImportedSiteConfigurationService(resolver: ImportedSiteConfigurationResolver): ApplyImportedSiteConfigurationService =
        ApplyImportedSiteConfigurationService(siteConfigurations, publications, resolver, states, clock, refreshPolicy)
}
