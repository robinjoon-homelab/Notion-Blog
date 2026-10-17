package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException

@Transactional(readOnly = true)
class GetPostListService(
    private val chromes: SiteChromeService,
    private val references: ContentReferencesService,
    private val publishedPosts: PublishedPostStore,
    private val snapshotFailures: SnapshotFailureReporter,
) : GetPostListUseCase {
    @Transactional(readOnly = true)
    override fun get(page: PageQuery): PostListLookupResult {
        val chrome = chromes.load() ?: return PostListLookupResult.ContentUnavailable
        val entries =
            try {
                publishedPosts.findListPage(chromes.siteDocumentPostIds(chrome.site), page)
            } catch (failure: SnapshotContentException) {
                snapshotFailures.report(failure, SnapshotFailureOperation.FEED_LOOKUP, null)
                return PostListLookupResult.ContentUnavailable
            }
        val layout = references.resolve(listOfNotNull(chrome.header, chrome.footer))
        val covers = references.media(entries.items.mapTo(linkedSetOf(), PostListEntry::revisionId))
        return PostListLookupResult.Found(PostListPage(chrome, entries, layout.copy(media = layout.media + covers)))
    }
}
