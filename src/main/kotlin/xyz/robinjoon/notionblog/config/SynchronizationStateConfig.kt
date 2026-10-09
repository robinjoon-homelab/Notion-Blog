package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.application.service.ActivatePublicationService
import xyz.robinjoon.notionblog.application.service.ApplyImportedPostService
import xyz.robinjoon.notionblog.application.service.ApplyImportedSiteConfigurationService
import xyz.robinjoon.notionblog.application.service.CancelInactivePostSynchronizationService
import xyz.robinjoon.notionblog.application.service.ImportedSiteConfigurationResolver
import xyz.robinjoon.notionblog.application.service.StagePublicationMemberService
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
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
        assets: PresentationAssetCatalog,
        defaultProfileKey: PresentationProfileKey,
        publicationIdFactory: () -> PublicationId,
    ): ImportedSiteConfigurationResolver =
        ImportedSiteConfigurationResolver(siteConfigurations, assets, defaultProfileKey, publicationIdFactory)

    @Bean
    fun applyImportedSiteConfigurationService(resolver: ImportedSiteConfigurationResolver): ApplyImportedSiteConfigurationService =
        ApplyImportedSiteConfigurationService(siteConfigurations, publications, resolver, states, clock, refreshPolicy)
}
