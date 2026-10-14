package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.AppliedSiteConfiguration
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.blog.domain.sync.SyncState
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Instant

@Transactional
class ApplyImportedSiteConfigurationService(
    private val siteConfigurationRepository: SiteConfigurationRepository,
    private val publicationRepository: PublicationRepository,
    private val configurationResolver: ImportedSiteConfigurationResolver,
    private val syncStateRepository: SyncStateRepository,
    private val clock: Clock,
    private val refreshPolicy: RefreshPolicy,
) {
    @Transactional
    fun apply(imported: ImportedSiteConfiguration): AppliedSiteConfiguration {
        val now = clock.instant()
        val current = siteConfigurationRepository.findCurrent()
        val configuration = configurationResolver.resolve(imported, current)

        if (current == null) {
            publicationRepository.save(BlogPublication.create(configuration.publicationId))
        }
        siteConfigurationRepository.save(configuration, now)
        recordSuccess(now)

        return AppliedSiteConfiguration(
            configuration = configuration,
            rootChanged = current?.documents?.root != imported.rootDocument,
        )
    }

    @Transactional
    fun recordFailure(kind: SyncFailureKind) {
        val now = clock.instant()
        val target = SyncTarget.SiteConfiguration
        val current = syncStateRepository.find(target)
        val nextFailureCount = Math.addExact(current?.failureCount ?: 0, 1)
        val refreshAfter = refreshPolicy.nextFailureRefreshAt(now, nextFailureCount)
        val updated =
            current?.recordFailure(kind, refreshAfter)
                ?: SyncState.schedule(target, now).recordFailure(kind, refreshAfter)

        syncStateRepository.save(updated)
    }

    private fun recordSuccess(now: Instant) {
        val target = SyncTarget.SiteConfiguration
        val refreshAfter = refreshPolicy.nextSuccessfulRefreshAt(now)
        val updated =
            syncStateRepository.find(target)?.recordSuccess(now, refreshAfter)
                ?: SyncState.schedule(target, now).recordSuccess(now, refreshAfter)

        syncStateRepository.save(updated)
    }
}
