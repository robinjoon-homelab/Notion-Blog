package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionMappingFailureDetails
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken

internal class NotionBlockRecovery(
    private val reserve: () -> Unit,
) {
    private val issues = mutableListOf<MigrationFailureDetail>()

    fun warnings(): List<MigrationFailureDetail> = issues.distinct()

    fun canRecoverAccess(failure: SourceException): Boolean =
        failure.diagnostic?.httpStatus in localizedAccessStatuses && canRecover(failure)

    fun missingBlock(
        block: NotionBlockEnvelope,
        children: List<BlockNode>,
        failure: SourceException,
    ): BlockNode {
        if (!canRecover(failure)) throw failure
        val detail = failure.diagnostic ?: throw failure
        return missing(block.id, block.type, detail, children)
    }

    fun missingChildren(
        blockId: String,
        blockType: String,
        failure: SourceException,
    ): List<BlockNode> {
        val detail = failure.diagnostic?.takeIf { canRecoverAccess(failure) } ?: throw failure
        reserve()
        return listOf(missing(blockId, blockType, detail, emptyList()).copy(id = BlockId("$blockId:unavailable-children")))
    }

    fun mapped(block: BlockNode): BlockNode =
        when (val content = block.content) {
            is UnsupportedBlockContent -> {
                if (content.unavailable) {
                    block
                } else {
                    missing(
                        block.id.value,
                        content.blockType,
                        unsupported,
                        block.children,
                    )
                }
            }

            is LayoutBlockContent.Table -> {
                block.copy(children = block.children.map { row -> tableRow(row, content.width) })
            }

            else -> {
                block
            }
        }

    fun layoutChildren(
        type: String,
        children: List<BlockNode>,
    ): List<BlockNode> =
        when (type) {
            "column_list" -> children.map { child -> wrapUnavailable(child, LayoutBlockContent.Column(null)) }
            "tab" -> children.map { child -> wrapUnavailable(child, LayoutBlockContent.TabItem(listOf(label), null)) }
            else -> children
        }

    private fun wrapUnavailable(
        child: BlockNode,
        layout: LayoutBlockContent,
    ): BlockNode {
        if (!child.isUnavailable()) return child
        reserve()
        return BlockNode(BlockId("${child.id.value}:layout"), layout, children = listOf(child))
    }

    private fun tableRow(
        row: BlockNode,
        width: Int,
    ): BlockNode {
        val cells = (row.content as? LayoutBlockContent.TableRow)?.cells
        if (cells?.size == width) return row
        if (!row.isUnavailable()) report(row.id.value, invalidTableRow)
        val replacement =
            buildList {
                repeat(width) { index ->
                    reserve()
                    val readable = cells?.getOrNull(index).orEmpty()
                    add(if (index == 0) readable + label else readable)
                }
            }
        return row.copy(content = LayoutBlockContent.TableRow(replacement))
    }

    private fun BlockNode.isUnavailable(): Boolean = (content as? UnsupportedBlockContent)?.unavailable == true

    private fun missing(
        blockId: String,
        blockType: String,
        detail: MigrationFailureDetail,
        children: List<BlockNode>,
    ): BlockNode {
        report(blockId, detail)
        return BlockNode(BlockId(blockId), UnsupportedBlockContent(blockType, unavailable = true), children = children)
    }

    private fun report(
        blockId: String,
        detail: MigrationFailureDetail,
    ) {
        issues += detail.copy(stage = MigrationFailureStage.BODY, blockId = NotionMappingFailureDetails.validatedBlockId(blockId))
    }

    private fun canRecover(failure: Throwable): Boolean =
        !Thread.currentThread().isInterrupted && generateSequence(failure) { it.cause }.none { it is InterruptedException }

    private companion object {
        val localizedAccessStatuses = setOf(403, 404)
        val label = InlineContent.Text("가져오지 못한 블록", TextAnnotations(bold = true, background = ColorToken.YELLOW))
        val unsupported = MigrationFailureDetail(MigrationFailureStage.BODY, "지원하지 않는 블록 형식입니다.", "UnsupportedBlockContent")
        val invalidTableRow = MigrationFailureDetail(MigrationFailureStage.BODY, "표의 열 수와 일치하지 않는 행입니다.", "NotionBlockMappingException")
    }
}
