package xyz.robinjoon.notionblog.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling.CatalogScanScheduler
import xyz.robinjoon.notionblog.blog.adapter.inbound.scheduling.SynchronizationScheduler
import xyz.robinjoon.notionblog.blog.adapter.inbound.startup.LegacyPostMigrationInitializer
import xyz.robinjoon.notionblog.blog.application.port.input.MigrateLegacyPostsUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizationQueryUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeCatalogUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizePostUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeSiteConfigurationUseCase
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class SchedulingConfig(
    private val queryService: SynchronizationQueryUseCase,
    private val siteConfigurationService: SynchronizeSiteConfigurationUseCase,
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
            postService = postService,
            clock = clock,
            dueBatchSize = properties.synchronization.dueBatchSize,
        )

    @Bean
    @ConditionalOnProperty(prefix = "blog.synchronization", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun catalogScanScheduler(catalogService: SynchronizeCatalogUseCase): CatalogScanScheduler = CatalogScanScheduler(catalogService)

    // 동기화 설정과 무관하게 등록한다. 요청을 처리하기 전에 레거시 글이 관리 대상이 되어야 하기 때문이다.
    @Bean
    fun legacyPostMigrationInitializer(migration: MigrateLegacyPostsUseCase): LegacyPostMigrationInitializer =
        LegacyPostMigrationInitializer(migration)
}
