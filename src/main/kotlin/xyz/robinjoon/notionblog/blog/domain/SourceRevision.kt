package xyz.robinjoon.notionblog.blog.domain

@JvmInline
value class SourceRevision(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "source revision must not be blank" }
    }
}
