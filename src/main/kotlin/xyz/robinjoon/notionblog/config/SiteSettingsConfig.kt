package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.blog.application.service.ApplyImportedSiteConfigurationService
import xyz.robinjoon.notionblog.blog.application.service.ImportedSiteConfigurationResolver
import xyz.robinjoon.notionblog.blog.application.service.RegisterPostService
import xyz.robinjoon.notionblog.blog.application.service.SyncStateRecorder
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class SiteSettingsConfig(
    private val siteConfigurations: SiteConfigurationRepository,
    private val publications: PublicationRepository,
    private val clock: Clock,
) {
    @Bean
    fun importedSiteConfigurationResolver(
        profiles: PresentationProfileRepository,
        assets: PresentationAssetCatalog,
        defaultProfileKey: PresentationProfileKey,
        publicationIdFactory: () -> PublicationId,
    ): ImportedSiteConfigurationResolver = ImportedSiteConfigurationResolver(profiles, assets, defaultProfileKey, publicationIdFactory)

    @Bean
    fun applyImportedSiteConfigurationService(
        resolver: ImportedSiteConfigurationResolver,
        recorder: SyncStateRecorder,
        registrations: RegisterPostService,
    ): ApplyImportedSiteConfigurationService =
        ApplyImportedSiteConfigurationService(siteConfigurations, publications, resolver, recorder, registrations, clock)
}
