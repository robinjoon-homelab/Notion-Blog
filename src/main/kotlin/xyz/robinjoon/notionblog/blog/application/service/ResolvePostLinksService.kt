package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.LinkResolution
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget

class ResolvePostLinksService(
    private val postRepository: PostRepository,
    private val publishedPosts: PublishedPostStore,
) {
    // 원본 문서로 가는 링크는 그 글이 애플리케이션에서 공개 상태일 때만 내부 링크가 된다. 그렇지 않으면 원래 URL이 비공개 원본
    // 페이지를 가리킬 수 있으므로 링크하지 않는다. 저장된 URL은 리비전에 그대로 남는다.
    fun resolve(trees: Collection<BlockTree>): Map<LinkTarget.SourceDocument, LinkResolution> {
        val targets = linkedSetOf<LinkTarget.SourceDocument>()
        trees.forEach { tree -> tree.roots.forEach { node -> collect(node, targets) } }

        val bindings = postRepository.findBindingsBySourceDocuments(targets.mapTo(linkedSetOf()) { it.reference })
        val publicPostIds = publishedPosts.findPublicPostIds(bindings.values.mapTo(linkedSetOf()) { it.postId })

        return targets.associateWith { target ->
            val binding = bindings[target.reference]
            when {
                binding != null && binding.postId in publicPostIds -> LinkResolution.Internal(binding.postId)
                else -> LinkResolution.Unlinked
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
}
