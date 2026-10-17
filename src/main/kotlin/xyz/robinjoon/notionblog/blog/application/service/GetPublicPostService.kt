package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// 공개 글의 게시 리비전을 제공한다. 비공개·보관·알 수 없는 글은 찾을 수 없음으로 처리한다.
@Transactional(readOnly = true)
class GetPublicPostService(
    private val chromes: SiteChromeService,
    private val references: ContentReferencesService,
    private val publishedPosts: PublishedPostStore,
    private val snapshotFailures: SnapshotFailureReporter,
) : GetPublicPostUseCase {
    @Transactional(readOnly = true)
    override fun get(postId: PostId): PublicPostLookupResult {
        val post =
            try {
                publishedPosts.find(postId) ?: return PublicPostLookupResult.NotFound
            } catch (failure: SnapshotContentException) {
                snapshotFailures.report(failure, SnapshotFailureOperation.POST_LOOKUP, postId)
                return PublicPostLookupResult.ContentUnavailable
            }
        val chrome = chromes.load() ?: return PublicPostLookupResult.ContentUnavailable
        val shown = listOfNotNull(post.revision, chrome.header, chrome.footer)
        return PublicPostLookupResult.Found(PublicPostPage(chrome, post, references.resolve(shown)))
    }
}
