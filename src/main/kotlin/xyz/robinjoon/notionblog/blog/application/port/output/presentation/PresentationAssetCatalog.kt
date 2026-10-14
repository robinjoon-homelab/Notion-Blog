package xyz.robinjoon.notionblog.blog.application.port.output.presentation

import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.ResolvedPresentationAsset
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef

interface PresentationAssetCatalog {
    fun resolve(reference: PresentationAssetRef): PresentationAssetDescriptor?

    fun resolveCurrent(key: String): ResolvedPresentationAsset?
}
