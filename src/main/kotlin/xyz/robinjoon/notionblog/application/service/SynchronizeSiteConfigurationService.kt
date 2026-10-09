package xyz.robinjoon.notionblog.application.service

import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.port.input.SynchronizeSiteConfigurationUseCase
import xyz.robinjoon.notionblog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind

class SynchronizeSiteConfigurationService(
    private val source: SiteConfigurationSource,
    private val applyService: ApplyImportedSiteConfigurationService,
    private val publicationService: SynchronizePublicationService,
) : SynchronizeSiteConfigurationUseCase {
    override fun synchronize() {
        val imported = fetch()
        val applied = apply(imported)

        if (applied.rootChanged) {
            publicationService.synchronize()
        }
    }

    private fun fetch() =
        try {
            source.fetch()
        } catch (exception: SourceException) {
            applyService.recordFailure(exception.toSyncFailureKind())
            throw exception
        }

    private fun apply(imported: ImportedSiteConfiguration) =
        try {
            applyService.apply(imported)
        } catch (exception: IllegalArgumentException) {
            applyService.recordFailure(SyncFailureKind.CONFIGURATION)
            throw exception
        }
}
