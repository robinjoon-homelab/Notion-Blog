package xyz.robinjoon.notionblog.blog.domain.site

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.AggregateRoot

// The applied site settings. It refers to the publication and the presentation profile by identity only.
@AggregateRoot
@ConsistentCopyVisibility
data class SiteConfiguration private constructor(
    val publicationId: PublicationId,
    val documents: SiteDocuments,
    val metadata: SiteMetadata,
    val presentationProfile: PresentationProfileRef,
) {
    companion object {
        fun create(
            publicationId: PublicationId,
            documents: SiteDocuments,
            metadata: SiteMetadata,
            presentationProfile: PresentationProfileRef,
        ): SiteConfiguration = SiteConfiguration(publicationId, documents, metadata, presentationProfile)

        fun restore(
            publicationId: PublicationId,
            documents: SiteDocuments,
            metadata: SiteMetadata,
            presentationProfile: PresentationProfileRef,
        ): SiteConfiguration = SiteConfiguration(publicationId, documents, metadata, presentationProfile)
    }
}

// The source documents that define the site: the publication root and the optional layout fragments.
data class SiteDocuments(
    val root: SourceDocumentRef,
    val header: SourceDocumentRef?,
    val footer: SourceDocumentRef?,
)

data class SiteMetadata(
    val siteName: String,
    val defaultDescription: String?,
    val languageTag: String,
    val favicon: PresentationAssetRef?,
) {
    init {
        require(siteName.isNotBlank()) { "site name must not be blank" }
        require(defaultDescription == null || defaultDescription.isNotBlank()) {
            "default description must not be blank when present"
        }
        require(languageTag.isNotBlank()) { "language tag must not be blank" }
    }
}
