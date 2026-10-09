package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.application.port.input.SynchronizePostUseCase
import xyz.robinjoon.notionblog.application.port.input.SynchronizeSiteConfigurationUseCase
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.application.service.ActivatePublicationService
import xyz.robinjoon.notionblog.application.service.ApplyImportedPostService
import xyz.robinjoon.notionblog.application.service.ApplyImportedSiteConfigurationService
import xyz.robinjoon.notionblog.application.service.CancelInactivePostSynchronizationService
import xyz.robinjoon.notionblog.application.service.StagePublicationMemberService
import xyz.robinjoon.notionblog.application.service.SynchronizationQueryService
import xyz.robinjoon.notionblog.application.service.SynchronizePostService
import xyz.robinjoon.notionblog.application.service.SynchronizePublicationService
import xyz.robinjoon.notionblog.application.service.SynchronizeSiteConfigurationService

@Configuration(proxyBeanMethods = false)
internal class SynchronizationConfig(
    private val queryService: SynchronizationQueryService,
    private val stageService: StagePublicationMemberService,
    private val applyService: ApplyImportedPostService,
    private val activateService: ActivatePublicationService,
    private val cancelService: CancelInactivePostSynchronizationService,
) {
    @Bean
    fun synchronizePublicationService(source: PostSource): SynchronizePublicationService =
        SynchronizePublicationService(queryService, stageService, source, applyService, activateService)

    @Bean
    fun synchronizePostUseCase(
        source: PostSource,
        publications: SynchronizePublicationService,
    ): SynchronizePostUseCase = SynchronizePostService(queryService, source, applyService, publications, cancelService)

    @Bean
    fun synchronizeSiteConfigurationUseCase(
        source: SiteConfigurationSource,
        applyConfiguration: ApplyImportedSiteConfigurationService,
        publications: SynchronizePublicationService,
    ): SynchronizeSiteConfigurationUseCase = SynchronizeSiteConfigurationService(source, applyConfiguration, publications)
}
