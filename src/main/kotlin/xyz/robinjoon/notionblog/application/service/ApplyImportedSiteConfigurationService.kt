package xyz.robinjoon.notionblog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.AppliedSiteConfiguration
import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
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
            publicationRepository.save(BlogPublication(configuration.publicationId, null, null))
        }
        siteConfigurationRepository.save(configuration, now)
        recordSuccess(now)

        return AppliedSiteConfiguration(
            configuration = configuration,
            rootChanged = current?.rootDocument != imported.rootDocument,
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
                ?: SyncState(target, null, refreshAfter, nextFailureCount, kind)

        syncStateRepository.save(updated)
    }

    private fun recordSuccess(now: Instant) {
        val target = SyncTarget.SiteConfiguration
        val refreshAfter = refreshPolicy.nextSuccessfulRefreshAt(now)
        val updated =
            syncStateRepository.find(target)?.recordSuccess(now, refreshAfter)
                ?: SyncState(target, now, refreshAfter, 0, null)

        syncStateRepository.save(updated)
    }
}
