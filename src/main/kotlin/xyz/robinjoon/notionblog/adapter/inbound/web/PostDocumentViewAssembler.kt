package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockStyleView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BreadcrumbItemView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BreadcrumbView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ChildPostView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DatabaseLinkView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DocumentLinkView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.HeadingLevelView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.InternalLinkView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ListItemView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ListTypeView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ListView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.MeetingNotesStatusView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.MeetingNotesView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.NumberedListFormatView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.PostDocumentView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.SynchronizedBlockView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.SynchronizedOriginView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TabContainerView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TableOfContentsEntryView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TableOfContentsView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TemplateView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.UnsupportedView
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget

internal class PostDocumentViewAssembler(
    private val post: Post,
    private val contentMapper: PostContentViewMapper,
    private val idPrefix: String,
) {
    private val tableOfContents = collectHeadings(post.content.roots)
    private val textBlocks = TextBlockViewAssembler(contentMapper)
    private val layoutBlocks = LayoutBlockViewAssembler(contentMapper)
    private val dataBlocks = DataBlockViewAssembler(contentMapper)
    private val mediaBlocks = MediaBlockViewAssembler(contentMapper)

    fun assemble(): PostDocumentView = PostDocumentView(post.title, assembleBlocks(post.content.roots))

    private fun assembleBlocks(nodes: List<BlockNode>): List<BlockView> {
        val views = mutableListOf<BlockView>()
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index]
            val listType = (node.content as? ListBlockContent)?.listType()
            if (listType == null) {
                views += assembleBlock(node)
                index += 1
            } else {
                val list = assembleList(nodes, index, listType)
                views += list
                index += list.items.size
            }
        }
        return views
    }

    private fun assembleList(
        nodes: List<BlockNode>,
        startIndex: Int,
        listType: ListTypeView,
    ): ListView {
        val grouped = mutableListOf<ListItemView>()
        var index = startIndex
        while (index < nodes.size && (nodes[index].content as? ListBlockContent)?.listType() == listType) {
            val node = nodes[index]
            val content = node.content as ListBlockContent
            if (grouped.isNotEmpty() && (content as? ListBlockContent.NumberedItem)?.startsNewList == true) break
            grouped +=
                textBlocks.listItem(content, idPrefix + node.id.value, BlockStyleViewMapper.map(node.style), assembleBlocks(node.children))
            index += 1
        }
        val first = nodes[startIndex]
        val numbered = first.content as? ListBlockContent.NumberedItem
        return ListView(
            idPrefix + first.id.value,
            listType,
            numbered?.startNumber,
            numbered?.displayFormat?.let { NumberedListFormatView.valueOf(it.name) },
            grouped,
            BlockStyleViewMapper.map(first.style),
        )
    }

    private fun assembleBlock(node: BlockNode): BlockView {
        val children = assembleBlocks(node.children)
        val style = BlockStyleViewMapper.map(node.style)
        val id = idPrefix + node.id.value
        return when (val content = node.content) {
            is TextBlockContent -> textBlocks.assemble(content, id, style, children)
            is ListBlockContent -> textBlocks.listItem(content, id, style, children)
            is LayoutBlockContent -> layoutBlocks.assemble(content, id, style, children)
            is DataViewContent -> dataBlocks.assemble(content, id, style)
            is MediaBlockContent -> mediaBlocks.assemble(content, id, style, children)
            is ReferenceBlockContent -> reference(content, node, children)
            is ReusableBlockContent -> reusable(content, id, style, children)
            is SpecialBlockContent.MeetingNotes -> meetingNotes(content, id, style, children)
            is UnsupportedBlockContent -> UnsupportedView(id, content.blockType, style, children)
        }
    }

    private fun reference(
        content: ReferenceBlockContent,
        node: BlockNode,
        children: List<BlockView>,
    ): BlockView {
        val id = idPrefix + node.id.value
        val style = BlockStyleViewMapper.map(node.style)
        return when (content) {
            is ReferenceBlockContent.ChildPost -> {
                ChildPostView(
                    id,
                    content.title,
                    contentMapper.linkView(LinkTarget.SourceDocument(content.reference, null)),
                    style,
                    children,
                )
            }

            is ReferenceBlockContent.DocumentLink -> {
                documentLink(content, id, style, children)
            }

            is ReferenceBlockContent.DatabaseLink -> {
                databaseLink(content, node, children)
            }

            is ReferenceBlockContent.Breadcrumb -> {
                BreadcrumbView(id, breadcrumbItems(content), style, children)
            }

            ReferenceBlockContent.TableOfContents -> {
                TableOfContentsView(id, tableOfContents, style, children)
            }
        }
    }

    private fun documentLink(
        content: ReferenceBlockContent.DocumentLink,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): DocumentLinkView =
        DocumentLinkView(
            id,
            contentMapper.linkView(LinkTarget.SourceDocument(content.reference, content.originalUrl)),
            WebContentUrls.external(content.originalUrl),
            style,
            children,
        )

    private fun databaseLink(
        content: ReferenceBlockContent.DatabaseLink,
        node: BlockNode,
        children: List<BlockView>,
    ): DatabaseLinkView =
        DatabaseLinkView(
            idPrefix + node.id.value,
            content.title,
            if (hasDataView(
                    node.children,
                )
            ) {
                null
            } else {
                contentMapper.linkView(LinkTarget.SourceDocument(content.reference, content.originalUrl))
            },
            WebContentUrls.external(content.originalUrl),
            BlockStyleViewMapper.map(node.style),
            children.map { child -> if (child is TabContainerView) child.copy(collapseSingleTab = true) else child },
        )

    private fun hasDataView(nodes: List<BlockNode>): Boolean = nodes.any { it.content is DataViewContent || hasDataView(it.children) }

    private fun reusable(
        content: ReusableBlockContent,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): BlockView =
        when (content) {
            is ReusableBlockContent.Synchronized -> {
                SynchronizedBlockView(
                    id,
                    content.origin?.let { SynchronizedOriginView(it.document.sourceId.value, it.document.externalId, it.blockExternalId) },
                    style,
                    children,
                )
            }

            is ReusableBlockContent.Template -> {
                TemplateView(id, contentMapper.inlineViews(content.title), style, children)
            }
        }

    private fun meetingNotes(
        content: SpecialBlockContent.MeetingNotes,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): MeetingNotesView =
        MeetingNotesView(
            id,
            content.title,
            MeetingNotesStatusView.valueOf(content.status.name),
            contentMapper.inlineViews(content.summary),
            content.notesReference?.let(contentMapper::linkView),
            style,
            children,
        )

    private fun breadcrumbItems(content: ReferenceBlockContent.Breadcrumb): List<BreadcrumbItemView> =
        if (content.items.isEmpty()) {
            listOf(BreadcrumbItemView("Home", InternalLinkView("/")), BreadcrumbItemView(post.title, null))
        } else {
            content.items.mapNotNull { target ->
                contentMapper.linkView(target)?.let { link -> BreadcrumbItemView(link.href, link) }
            }
        }

    private fun collectHeadings(nodes: List<BlockNode>): List<TableOfContentsEntryView> =
        buildList {
            nodes.forEach { node ->
                val heading = node.content as? TextBlockContent.Heading
                if (heading != null) {
                    add(
                        TableOfContentsEntryView(
                            contentMapper.plainText(heading.richText),
                            "#$idPrefix${node.id.value}",
                            HeadingLevelView.valueOf(heading.level.name),
                        ),
                    )
                }
                addAll(collectHeadings(node.children))
            }
        }

    private fun ListBlockContent.listType(): ListTypeView =
        when (this) {
            is ListBlockContent.BulletedItem -> ListTypeView.BULLETED
            is ListBlockContent.NumberedItem -> ListTypeView.NUMBERED
            is ListBlockContent.ToDoItem -> ListTypeView.TODO
        }
}
