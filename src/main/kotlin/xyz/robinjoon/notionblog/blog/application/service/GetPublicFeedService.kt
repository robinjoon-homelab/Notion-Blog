package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.PublicFeed
import xyz.robinjoon.notionblog.blog.application.model.PublicFeedLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicFeedUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException

// 최신 공개 글 목록이다. 항목 식별자와 날짜는 글 ID와 최초 게시 시각에서 가져온다.
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class GetPublicFeedService(
    private val siteConfigurations: SiteConfigurationRepository,
    private val chromes: SiteChromeService,
    private val publishedPosts: PublishedPostStore,
    private val snapshotFailures: SnapshotFailureReporter,
) : GetPublicFeedUseCase {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun get(): PublicFeedLookupResult {
        val site = siteConfigurations.findCurrent() ?: return PublicFeedLookupResult.ContentUnavailable
        return try {
            val entries = publishedPosts.findRecent(chromes.siteDocumentPostIds(site), FEED_SIZE)
            PublicFeedLookupResult.Found(PublicFeed(site.metadata, entries))
        } catch (failure: SnapshotContentException) {
            snapshotFailures.report(failure, SnapshotFailureOperation.FEED_LOOKUP, null)
            PublicFeedLookupResult.ContentUnavailable
        }
    }

    private companion object {
        const val FEED_SIZE = 20
    }
}
