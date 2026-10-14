package xyz.robinjoon.notionblog.blog.application.model

data class PresentationAssetDescriptor(
    val publicPath: String,
    val mediaType: String,
    val integrity: String,
)
