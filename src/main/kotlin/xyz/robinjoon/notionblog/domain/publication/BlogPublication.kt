package xyz.robinjoon.notionblog.domain.publication

import xyz.robinjoon.notionblog.domain.post.PostId

data class BlogPublication(
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
}
