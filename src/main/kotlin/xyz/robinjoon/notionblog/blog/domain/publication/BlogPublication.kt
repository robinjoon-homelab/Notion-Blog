package xyz.robinjoon.notionblog.blog.domain.publication

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.AggregateRoot

// Owns the root post and the active revision pointer. Revisions and their members are loaded by revision rather than
// held here, but activation changes the pointer and both revision states in one transaction.
@AggregateRoot
@ConsistentCopyVisibility
data class BlogPublication private constructor(
    val id: PublicationId,
    val rootPostId: PostId?,
    val activeRevisionId: PublicationRevisionId?,
) {
    init {
        require((rootPostId == null) == (activeRevisionId == null)) {
            "publication root and active revision must be set together"
        }
    }

    fun activate(
        rootPostId: PostId,
        revisionId: PublicationRevisionId,
    ): BlogPublication =
        copy(
            rootPostId = rootPostId,
            activeRevisionId = revisionId,
        )

    companion object {
        // A new publication has no root or active revision until its first revision is activated.
        fun create(id: PublicationId): BlogPublication = BlogPublication(id, null, null)

        fun restore(
            id: PublicationId,
            rootPostId: PostId?,
            activeRevisionId: PublicationRevisionId?,
        ): BlogPublication = BlogPublication(id, rootPostId, activeRevisionId)
    }
}
