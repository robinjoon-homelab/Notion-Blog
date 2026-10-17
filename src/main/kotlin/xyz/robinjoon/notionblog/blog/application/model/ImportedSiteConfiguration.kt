package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey

data class ImportedSiteConfiguration(
    val rootDocument: SourceDocumentRef?,
    val headerDocument: SourceDocumentRef?,
    val footerDocument: SourceDocumentRef?,
    val metadata: ImportedSiteMetadata,
    val presentationProfileKey: PresentationProfileKey?,
)

data class ImportedSiteMetadata(
    val siteName: String,
    val defaultDescription: String?,
    val languageTag: String,
    val faviconAssetKey: String?,
)
