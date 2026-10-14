package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef

data class ResolvedPresentationAsset(
    val reference: PresentationAssetRef,
    val descriptor: PresentationAssetDescriptor,
)
