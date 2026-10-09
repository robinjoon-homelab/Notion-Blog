package xyz.robinjoon.notionblog.domain.publication

import java.util.UUID

@JvmInline
value class PublicationRevisionId(
    val value: UUID,
)

data class PublicationRevision(
    val id: PublicationRevisionId,
    val publicationId: PublicationId,
    val state: PublicationRevisionState,
) {
    fun activate(): PublicationRevision {
        require(state == PublicationRevisionState.STAGING) { "only staging revisions can be activated" }
        return copy(state = PublicationRevisionState.ACTIVE)
    }

    fun supersede(): PublicationRevision {
        require(state == PublicationRevisionState.ACTIVE) { "only active revisions can be superseded" }
        return copy(state = PublicationRevisionState.SUPERSEDED)
    }

    fun abandon(): PublicationRevision {
        require(state == PublicationRevisionState.STAGING) { "only staging revisions can be abandoned" }
        return copy(state = PublicationRevisionState.ABANDONED)
    }
}

enum class PublicationRevisionState {
    STAGING,
    ACTIVE,
    SUPERSEDED,
    ABANDONED,
}
