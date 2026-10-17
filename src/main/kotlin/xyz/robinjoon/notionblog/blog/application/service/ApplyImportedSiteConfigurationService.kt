package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock

// 사이트 설정을 적용한다. 루트·헤더·푸터 문서는 관리자가 게시할 수 있도록 비공개 관리 글이 되며, 설정 자체는 아무것도 게시하지 않는다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class ApplyImportedSiteConfigurationService(
    private val siteConfigurationRepository: SiteConfigurationRepository,
    private val publicationRepository: PublicationRepository,
    private val configurationResolver: ImportedSiteConfigurationResolver,
    private val recorder: SyncStateRecorder,
    private val registrations: RegisterPostService,
    private val clock: Clock,
) {
    @Transactional(rollbackFor = [Exception::class])
    fun apply(imported: ImportedSiteConfiguration): SiteConfiguration {
        val now = clock.instant()
        val current = siteConfigurationRepository.findCurrent()
        val configuration = configurationResolver.resolve(imported, current)

        // 사이트 설정 행이 여전히 레거시 게시를 참조하므로 그 레거시 행은 이력으로 남긴다.
        if (current == null) {
            publicationRepository.save(BlogPublication.create(configuration.publicationId))
        }
        siteConfigurationRepository.save(configuration, now)
        registrations.registerSourceDocuments(configuration.documents.all)
        recorder.recordSuccess(SyncTarget.SiteConfiguration, now)
        return configuration
    }

    @Transactional(rollbackFor = [Exception::class])
    fun recordFailure(kind: SyncFailureKind) = recorder.recordFailure(SyncTarget.SiteConfiguration, kind, clock.instant())
}
