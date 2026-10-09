package xyz.robinjoon.notionblog.application.service

import xyz.robinjoon.notionblog.application.port.input.SynchronizePublicationUseCase
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import java.util.ArrayDeque

class SynchronizePublicationService(
    private val synchronizationQueryService: SynchronizationQueryService,
    private val stagePublicationMemberService: StagePublicationMemberService,
    private val postSource: PostSource,
    private val applyImportedPostService: ApplyImportedPostService,
    private val activatePublicationService: ActivatePublicationService,
) : SynchronizePublicationUseCase {
    override fun synchronize() {
        val context = synchronizationQueryService.loadPublication() ?: return
        val revision = stagePublicationMemberService.begin(context.publicationId)

        try {
            collectMembers(revision.id, context.rootDocument)
            activatePublicationService.activate(revision.id)
        } catch (exception: SourceException) {
            stagePublicationMemberService.abandon(revision.id, exception.toSyncFailureKind())
            throw exception
        } catch (exception: IllegalArgumentException) {
            stagePublicationMemberService.abandon(revision.id, SyncFailureKind.MAPPING)
            throw exception
        } catch (exception: IllegalStateException) {
            stagePublicationMemberService.abandon(revision.id, SyncFailureKind.MAPPING)
            throw exception
        }
    }

    private fun collectMembers(
        revisionId: PublicationRevisionId,
        rootDocument: SourceDocumentRef,
    ) {
        val scheduled = mutableSetOf(rootDocument)
        val queue = ArrayDeque<PendingDocument>()
        queue += PendingDocument(rootDocument, parentPostId = null, depth = 0)

        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val imported = postSource.fetch(pending.sourceDocument)
            require(imported.sourceDocument == pending.sourceDocument) {
                "source returned a document different from the requested reference"
            }

            val postId = applyImportedPostService.apply(imported)
            stagePublicationMemberService.stage(
                PublicationMember(revisionId, postId, pending.parentPostId, pending.depth),
            )

            imported.containedChildren.forEach { child ->
                require(scheduled.add(child)) { "structural publication graph revisits a source document" }
                queue += PendingDocument(child, postId, pending.depth + 1)
            }
        }
    }

    private data class PendingDocument(
        val sourceDocument: SourceDocumentRef,
        val parentPostId: PostId?,
        val depth: Int,
    )
}
