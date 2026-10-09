package xyz.robinjoon.notionblog.documentation

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.service.ActivatePublicationService
import xyz.robinjoon.notionblog.application.service.ApplyImportedPostService
import xyz.robinjoon.notionblog.application.service.ApplyImportedSiteConfigurationService
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class DocumentedBlogFixture(
    private val importedPosts: ApplyImportedPostService,
    private val importedSettings: ApplyImportedSiteConfigurationService,
    private val activation: ActivatePublicationService,
    private val publications: PublicationRepository,
    dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
) {
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(transactionManager)
    private val root = imported("root", "Documentation blog")
    private val article = imported("article", "A published article")

    fun clearContent() {
        jdbc.execute(
            "truncate table site_configuration, publication_member, publication_revision, publication, " +
                "post_availability, post_snapshot, post_source_binding, post, sync_state cascade",
        )
    }

    fun publishBlog(): PostId {
        val rootId = importedPosts.apply(root)
        val articleId = importedPosts.apply(article)
        val site =
            importedSettings.apply(
                ImportedSiteConfiguration(
                    rootDocument = root.sourceDocument,
                    headerDocument = null,
                    footerDocument = null,
                    metadata = ImportedSiteMetadata("Documentation blog", "Published notes", "en", null),
                    presentationProfileKey = null,
                ),
            )
        activate(site.configuration.publicationId, rootId, articleId)
        return articleId
    }

    fun unpublishRoot() {
        importedPosts.apply(root.copy(publicationStatus = ImportedPublicationStatus.UNPUBLISHED))
    }

    fun unpublishArticle() {
        importedPosts.apply(article.copy(publicationStatus = ImportedPublicationStatus.UNPUBLISHED))
    }

    private fun activate(
        publicationId: PublicationId,
        rootId: PostId,
        articleId: PostId,
    ) {
        transactions.executeWithoutResult {
            val revision = PublicationRevision(PublicationRevisionId(UUID.randomUUID()), publicationId, PublicationRevisionState.STAGING)
            publications.createRevision(revision, Instant.parse("2026-10-08T00:00:00Z"))
            publications.saveMembers(
                revision.id,
                listOf(PublicationMember(revision.id, rootId, null, 0), PublicationMember(revision.id, articleId, rootId, 1)),
            )
            activation.activate(revision.id)
        }
    }

    private fun imported(
        externalId: String,
        title: String,
    ) = ImportedPost(
        sourceDocument = SourceDocumentRef(SourceId("documentation"), externalId),
        title = title,
        publicationStatus = ImportedPublicationStatus.PUBLISHED,
        sourceRevision = SourceRevision("revision-1"),
        content = BlockTree(listOf(BlockNode(BlockId("paragraph"), TextBlockContent.Paragraph(listOf(InlineContent.Text(title)))))),
        containedChildren = emptyList(),
    )
}
