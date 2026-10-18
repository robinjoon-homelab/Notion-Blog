package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.HomePageQuery
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.application.model.PublishedListQuery
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.post.PostId

@Transactional(readOnly = true)
class GetPostListService(
    private val chromes: SiteChromeService,
    private val references: ContentReferencesService,
    private val publishedPosts: PublishedPostStore,
    private val snapshotFailures: SnapshotFailureReporter,
    private val homeConfigurations: HomeConfigurationRepository,
    private val categories: CategoryRepository,
) : GetPostListUseCase {
    @Transactional(readOnly = true)
    override fun get(query: HomePageQuery): PostListLookupResult {
        val chrome = chromes.load() ?: return PostListLookupResult.ContentUnavailable
        if (query.categoryId != null && categories.find(query.categoryId) == null) return PostListLookupResult.NotFound
        val configuration = homeConfigurations.find() ?: HomeConfiguration.defaults()
        val excluded = chromes.siteDocumentPostIds(chrome.site)
        val entries = loadEntries(excluded, query, configuration) ?: return PostListLookupResult.ContentUnavailable
        val layout = references.resolve(listOfNotNull(chrome.header, chrome.footer))
        val covers = references.media(entries.items.mapTo(linkedSetOf(), PostListEntry::revisionId))
        return PostListLookupResult.Found(
            PostListPage(
                chrome = chrome,
                entries = entries,
                references = layout.copy(media = layout.media + covers),
                configuration = configuration,
                categories = publishedPosts.findCategoryCounts(excluded),
                selectedCategoryId = query.categoryId,
                search = query.search,
                totalPublished = publishedPosts.countListed(excluded),
            ),
        )
    }

    private fun loadEntries(
        excluded: Set<PostId>,
        query: HomePageQuery,
        configuration: HomeConfiguration,
    ): ResultPage<PostListEntry>? =
        try {
            publishedPosts.findListPage(
                excluded,
                PublishedListQuery(PageQuery(query.page, configuration.pageSize), configuration.order, query.categoryId, query.search),
            )
        } catch (failure: SnapshotContentException) {
            snapshotFailures.report(failure, SnapshotFailureOperation.FEED_LOOKUP, null)
            null
        }
}
