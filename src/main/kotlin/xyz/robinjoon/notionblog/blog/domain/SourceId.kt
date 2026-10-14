package xyz.robinjoon.notionblog.blog.domain

@JvmInline
value class SourceId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "source id must not be blank" }
    }
}
