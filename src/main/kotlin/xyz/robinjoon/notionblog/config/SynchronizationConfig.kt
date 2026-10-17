package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeCatalogUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeSiteConfigurationUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaGateway
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostCatalog
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.blog.application.service.ApplyImportedPostService
import xyz.robinjoon.notionblog.blog.application.service.ApplyImportedSiteConfigurationService
import xyz.robinjoon.notionblog.blog.application.service.CancelSynchronizationService
import xyz.robinjoon.notionblog.blog.application.service.CapturePostMediaService
import xyz.robinjoon.notionblog.blog.application.service.RegisterPostService
import xyz.robinjoon.notionblog.blog.application.service.SynchronizationQueryService
import xyz.robinjoon.notionblog.blog.application.service.SynchronizeCatalogService
import xyz.robinjoon.notionblog.blog.application.service.SynchronizePostService
import xyz.robinjoon.notionblog.blog.application.service.SynchronizeSiteConfigurationService
import java.time.Clock

// 원본을 호출하거나 파일을 내려받는 조정 서비스다. 네트워크를 기다리는 동안 트랜잭션을 잡지 않는다.
@Configuration(proxyBeanMethods = false)
internal class SynchronizationConfig(
    private val catalog: PostCatalog,
    private val registrations: RegisterPostService,
    private val failures: OperationFailureReporter,
    private val cancelService: CancelSynchronizationService,
    private val clock: Clock,
) {
    // 가져오기 한 번의 총량 한도는 MEDIA 설정이며, 애플리케이션 서비스는 그 숫자만 받는다.
    @Bean
    fun capturePostMediaService(
        gateway: MediaGateway,
        mediaProperties: MediaProperties,
    ): CapturePostMediaService = CapturePostMediaService(gateway, mediaProperties.maxImportBytes, failures, clock)

    // 빈 하나가 예약 동기화와 관리자 새로고침을 모두 처리한다.
    @Bean
    fun synchronizePostService(
        queries: SynchronizationQueryService,
        source: PostRevisionSource,
        media: CapturePostMediaService,
        applyService: ApplyImportedPostService,
    ): SynchronizePostService = SynchronizePostService(queries, source, media, applyService, cancelService, failures)

    @Bean
    fun synchronizeCatalogUseCase(): SynchronizeCatalogUseCase = SynchronizeCatalogService(catalog, registrations)

    @Bean
    fun synchronizeSiteConfigurationUseCase(
        source: SiteConfigurationSource,
        applyConfiguration: ApplyImportedSiteConfigurationService,
    ): SynchronizeSiteConfigurationUseCase = SynchronizeSiteConfigurationService(source, applyConfiguration)
}
