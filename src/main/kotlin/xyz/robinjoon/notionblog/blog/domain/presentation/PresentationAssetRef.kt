package xyz.robinjoon.notionblog.blog.domain.presentation

data class PresentationAssetRef(
    val key: String,
    val version: Long,
    val integrity: String,
) {
    init {
        require(key.isNotBlank()) { "presentation asset key must not be blank" }
        require(version >= 0) { "presentation asset version cannot be negative" }
        require(integrity.isNotBlank()) { "presentation asset integrity must not be blank" }
    }
}
