package xyz.robinjoon.notionblog.domain.post.block

import xyz.robinjoon.notionblog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.style.BlockStyle

@JvmInline
value class BlockId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "block id must not be blank" }
    }
}

data class BlockNode(
    val id: BlockId,
    val content: BlockContent,
    val style: BlockStyle = BlockStyle.default,
    val children: List<BlockNode> = emptyList(),
)

data class BlockTree(
    val roots: List<BlockNode>,
) {
    init {
        val ids = mutableSetOf<BlockId>()
        roots.forEach { node -> validate(node, ids) }
    }

    private fun validate(
        node: BlockNode,
        ids: MutableSet<BlockId>,
    ) {
        require(ids.add(node.id)) { "block ids must be unique within a block tree" }
        when (val content = node.content) {
            LayoutBlockContent.ColumnList -> {
                require(node.children.all { it.content is LayoutBlockContent.Column }) {
                    "column lists may only contain columns"
                }
            }

            LayoutBlockContent.TabContainer -> {
                require(node.children.all { it.content is LayoutBlockContent.TabItem }) {
                    "tab containers may only contain tab items"
                }
            }

            is LayoutBlockContent.Table -> {
                require(node.children.all { it.content is LayoutBlockContent.TableRow }) {
                    "tables may only contain table rows"
                }
                require(node.children.all { (it.content as LayoutBlockContent.TableRow).cells.size == content.width }) {
                    "table rows must contain exactly the table width"
                }
            }

            else -> {
                Unit
            }
        }
        node.children.forEach { child -> validate(child, ids) }
    }
}
