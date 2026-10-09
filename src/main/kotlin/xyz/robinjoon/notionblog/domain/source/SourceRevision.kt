package xyz.robinjoon.notionblog.domain.source

@JvmInline
value class SourceRevision(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "source revision must not be blank" }
    }
}
