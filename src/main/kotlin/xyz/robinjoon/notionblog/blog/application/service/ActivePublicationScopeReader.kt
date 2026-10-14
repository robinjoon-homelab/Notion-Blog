package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.ActivePublicationScope
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionState

// Callers run this inside their own read-only transaction so the scope and the listed posts come from one snapshot.
class ActivePublicationScopeReader(
    private val posts: PostRepository,
    private val publications: PublicationRepository,
    private val siteConfigurations: SiteConfigurationRepository,
) {
    fun read(): ActivePublicationScope? {
        val site = siteConfigurations.findCurrent() ?: return null
        val publication = publications.findCurrent()?.takeIf { it.id == site.publicationId } ?: return null
        val rootPostId = publication.rootPostId ?: return null
        val activeRevisionId = publication.activeRevisionId ?: return null
        val revision = publications.findActiveRevision(publication.id) ?: return null
        if (revision.id != activeRevisionId || revision.publicationId != publication.id ||
            revision.state != PublicationRevisionState.ACTIVE
        ) {
            return null
        }

        val layoutReferences = setOfNotNull(site.documents.header, site.documents.footer)
        val layoutPostIds = posts.findBindingsBySourceDocuments(layoutReferences).values.mapTo(linkedSetOf()) { it.postId }
        return ActivePublicationScope(site, publication.id, rootPostId, layoutPostIds)
    }
}
