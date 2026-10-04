package xyz.robinjoon.notionblog.application.service

import org.springframework.stereotype.Service
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind

@Service
class SynchronizePostService(
    private val queryService: SynchronizationQueryService,
    private val source: PostSource,
    private val applyService: ApplyImportedPostService,
    private val publicationService: SynchronizePublicationService,
    private val cancelService: CancelInactivePostSynchronizationService,
) {
    fun synchronize(postId: PostId) {
        val context = queryService.loadPost(postId)
        if (context == null) {
            cancelService.cancelIfInactive(postId)
            return
        }
        val imported = fetch(postId, context.sourceDocument)
        apply(postId, context.sourceDocument, imported)

        if (imported.containedChildren.toSet() != context.activeDirectChildren) {
            publicationService.synchronize()
        }
    }

    private fun fetch(postId: PostId, sourceDocument: SourceDocumentRef): ImportedPost = try {
        source.fetch(sourceDocument)
    } catch (exception: SourceException) {
        applyService.recordFailure(postId, exception.toSyncFailureKind())
        throw exception
    }

    private fun apply(
        postId: PostId,
        expectedSourceDocument: SourceDocumentRef,
        imported: ImportedPost,
    ) {
        try {
            require(imported.sourceDocument == expectedSourceDocument) {
                "imported post source document must match the requested source document"
            }
            applyService.apply(imported)
        } catch (exception: IllegalArgumentException) {
            applyService.recordFailure(postId, SyncFailureKind.MAPPING)
            throw exception
        }
    }
}
