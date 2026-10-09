package xyz.robinjoon.notionblog.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import xyz.robinjoon.notionblog.adapter.inbound.scheduling.SynchronizationScheduler
import xyz.robinjoon.notionblog.application.port.input.SynchronizationQueryUseCase
import xyz.robinjoon.notionblog.application.port.input.SynchronizePostUseCase
import xyz.robinjoon.notionblog.application.port.input.SynchronizePublicationUseCase
import xyz.robinjoon.notionblog.application.port.input.SynchronizeSiteConfigurationUseCase
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class SchedulingConfig(
    private val queryService: SynchronizationQueryUseCase,
    private val siteConfigurationService: SynchronizeSiteConfigurationUseCase,
    private val publicationService: SynchronizePublicationUseCase,
    private val postService: SynchronizePostUseCase,
) {
    @Bean
    @ConditionalOnProperty(prefix = "blog.synchronization", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun synchronizationScheduler(
        clock: Clock,
        properties: BlogProperties,
    ): SynchronizationScheduler =
        SynchronizationScheduler(
            queryService = queryService,
            siteConfigurationService = siteConfigurationService,
            publicationService = publicationService,
            postService = postService,
            clock = clock,
            dueBatchSize = properties.synchronization.dueBatchSize,
        )
}
