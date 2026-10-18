package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.model.SiteChrome
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicSeriesUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedSeriesStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 공개 글이 있는 시리즈와 그 공개 글만 보여 준다. 사이트 문서는 공개 글이어도 빼며, 표지는 목록 항목의 공개본 리비전에서만 찾는다.
@Transactional(readOnly = true)
class GetPublicSeriesService(
    private val chromes: SiteChromeService,
    private val references: ContentReferencesService,
    private val publishedSeries: PublishedSeriesStore,
    private val homeConfigurations: HomeConfigurationRepository,
    private val snapshotFailures: SnapshotFailureReporter,
) : GetPublicSeriesUseCase {
    @Transactional(readOnly = true)
    override fun list(): PublicSeriesListLookupResult {
        val chrome = chromes.load() ?: return PublicSeriesListLookupResult.ContentUnavailable
        val summaries =
            try {
                publishedSeries.findAll(chromes.siteDocumentPostIds(chrome.site))
            } catch (failure: SnapshotContentException) {
                snapshotFailures.report(failure, SnapshotFailureOperation.FEED_LOOKUP, null)
                return PublicSeriesListLookupResult.ContentUnavailable
            }
        val covers = references.media(summaries.mapTo(linkedSetOf()) { it.firstPost.revisionId })
        return PublicSeriesListLookupResult.Found(PublicSeriesListPage(chrome, summaries, layoutWith(chrome, covers)))
    }

    @Transactional(readOnly = true)
    override fun get(id: SeriesId): PublicSeriesLookupResult {
        val chrome = chromes.load() ?: return PublicSeriesLookupResult.ContentUnavailable
        val series =
            try {
                publishedSeries.find(id, chromes.siteDocumentPostIds(chrome.site)) ?: return PublicSeriesLookupResult.NotFound
            } catch (failure: SnapshotContentException) {
                snapshotFailures.report(failure, SnapshotFailureOperation.FEED_LOOKUP, null)
                return PublicSeriesLookupResult.ContentUnavailable
            }
        val covers = references.media(series.posts.mapTo(linkedSetOf(), PostListEntry::revisionId))
        val configuration = homeConfigurations.find() ?: HomeConfiguration.defaults()
        return PublicSeriesLookupResult.Found(PublicSeriesPage(chrome, series, layoutWith(chrome, covers), configuration))
    }

    private fun layoutWith(
        chrome: SiteChrome,
        covers: RevisionMedia,
    ): ContentReferences {
        val layout = references.resolve(listOfNotNull(chrome.header, chrome.footer))
        return layout.copy(media = layout.media + covers)
    }
}
