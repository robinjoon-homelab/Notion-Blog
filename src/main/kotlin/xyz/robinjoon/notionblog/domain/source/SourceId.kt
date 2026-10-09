package xyz.robinjoon.notionblog.domain.source

@JvmInline
value class SourceId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "source id must not be blank" }
    }
}
