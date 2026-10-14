package xyz.robinjoon.notionblog.blog.domain

data class SourceDocumentRef(
    val sourceId: SourceId,
    val externalId: String,
) {
    init {
        require(externalId.isNotBlank()) { "source document external id must not be blank" }
    }
}
