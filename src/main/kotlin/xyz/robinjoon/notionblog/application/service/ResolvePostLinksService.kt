package xyz.robinjoon.notionblog.application.service

import xyz.robinjoon.notionblog.application.model.LinkResolution
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import java.net.URI

class ResolvePostLinksService(
    private val postRepository: PostRepository,
    private val publicationRepository: PublicationRepository,
) {
    fun resolve(
        publicationId: PublicationId,
        trees: Collection<BlockTree>,
    ): Map<LinkTarget.SourceDocument, LinkResolution> {
        val targets = linkedSetOf<LinkTarget.SourceDocument>()
        trees.forEach { tree -> tree.roots.forEach { node -> collect(node, targets) } }

        val bindings = postRepository.findBindingsBySourceDocuments(targets.mapTo(linkedSetOf()) { it.reference })
        val activePostIds =
            publicationRepository.findActiveMemberPostIds(
                publicationId,
                bindings.values.mapTo(linkedSetOf()) { it.postId },
            )

        return targets.associateWith { target ->
            val binding = bindings[target.reference]
            when {
                binding != null && binding.postId in activePostIds -> LinkResolution.Internal(binding.postId)
                else -> target.originalUrl.safeExternalOrUnlinked()
            }
        }
    }

    private fun collect(
        node: BlockNode,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        collect(node.content, targets)
        node.children.forEach { child -> collect(child, targets) }
    }

    private fun collect(
        content: BlockContent,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        when (content) {
            is TextBlockContent -> collect(content, targets)
            is ListBlockContent -> collect(content.richText, targets)
            is LayoutBlockContent -> collect(content, targets)
            is DataViewContent -> collect(content, targets)
            is MediaBlockContent -> collect(content, targets)
            is ReferenceBlockContent -> collect(content, targets)
            is SpecialBlockContent.MeetingNotes -> collect(content, targets)
            else -> Unit
        }
    }

    private fun collect(
        content: TextBlockContent,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        collect(content.richText, targets)
        if (content is TextBlockContent.Code) {
            collect(content.caption, targets)
        }
    }

    private fun collect(
        content: LayoutBlockContent,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        when (content) {
            is LayoutBlockContent.TabItem -> collect(content.title, targets)
            is LayoutBlockContent.TableRow -> content.cells.forEach { collect(it, targets) }
            else -> Unit
        }
    }

    private fun collect(
        content: DataViewContent,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        content.data.rows.forEach { row ->
            row.cells.forEach { collect(it, targets) }
            row.link?.let { collect(it, targets) }
        }
    }

    private fun collect(
        content: MediaBlockContent,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        when (content) {
            is MediaBlockContent.Media -> collect(content.caption, targets)
            is MediaBlockContent.Bookmark -> collect(content.caption, targets)
            is MediaBlockContent.Embed -> collect(content.caption, targets)
            is MediaBlockContent.LinkPreview -> Unit
        }
    }

    private fun collect(
        content: ReferenceBlockContent,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        when (content) {
            is ReferenceBlockContent.ChildPost -> targets += LinkTarget.SourceDocument(content.reference, null)
            is ReferenceBlockContent.DocumentLink -> targets += LinkTarget.SourceDocument(content.reference, content.originalUrl)
            is ReferenceBlockContent.DatabaseLink -> targets += LinkTarget.SourceDocument(content.reference, content.originalUrl)
            is ReferenceBlockContent.Breadcrumb -> content.items.forEach { collect(it, targets) }
            is ReferenceBlockContent.TableOfContents -> Unit
        }
    }

    private fun collect(
        content: SpecialBlockContent.MeetingNotes,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        collect(content.summary, targets)
        content.notesReference?.let { collect(it, targets) }
    }

    private fun collect(
        inlines: List<InlineContent>,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        inlines.forEach { inline ->
            when (inline) {
                is InlineContent.Text -> inline.link?.let { collect(it, targets) }
                is InlineContent.Mention -> inline.target?.let { collect(it, targets) }
                is InlineContent.Equation -> Unit
            }
        }
    }

    private fun collect(
        target: LinkTarget,
        targets: MutableSet<LinkTarget.SourceDocument>,
    ) {
        if (target is LinkTarget.SourceDocument) {
            targets += target
        }
    }

    private fun URI?.safeExternalOrUnlinked(): LinkResolution =
        if (this != null && scheme?.lowercase() in setOf("http", "https") &&
            host != null
        ) {
            LinkResolution.External(this)
        } else {
            LinkResolution.Unlinked
        }
}
