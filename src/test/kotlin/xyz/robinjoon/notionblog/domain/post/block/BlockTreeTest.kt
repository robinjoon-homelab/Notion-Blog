package xyz.robinjoon.notionblog.domain.post.block

import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent

class BlockTreeTest {
    @Test
    fun `rejects duplicate block ids anywhere in a tree`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                BlockTree(
                    listOf(
                        node(
                            "repeated",
                            TextBlockContent.Paragraph(emptyList()),
                            children = listOf(node("repeated", TextBlockContent.Quote(emptyList()))),
                        ),
                    ),
                )
            }.withMessage("block ids must be unique within a block tree")
    }

    @Test
    fun `requires every table row to have the table width`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                BlockTree(
                    listOf(
                        node(
                            "table",
                            LayoutBlockContent.Table(width = 2),
                            children = listOf(node("row", LayoutBlockContent.TableRow(cells = listOf(listOf(text("one")))))),
                        ),
                    ),
                )
            }.withMessage("table rows must contain exactly the table width")
    }

    @Test
    fun `allows only columns as direct children of a column list`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                BlockTree(
                    listOf(
                        node(
                            "columns",
                            LayoutBlockContent.ColumnList,
                            children = listOf(node("paragraph", TextBlockContent.Paragraph(emptyList()))),
                        ),
                    ),
                )
            }.withMessage("column lists may only contain columns")
    }

    @Test
    fun `allows only tab items as direct children of a tab container`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                BlockTree(
                    listOf(
                        node(
                            "tabs",
                            LayoutBlockContent.TabContainer,
                            children = listOf(node("paragraph", TextBlockContent.Paragraph(emptyList()))),
                        ),
                    ),
                )
            }.withMessage("tab containers may only contain tab items")
    }

    private fun node(
        id: String,
        content: BlockContent,
        children: List<BlockNode> = emptyList(),
    ) = BlockNode(BlockId(id), content, children = children)

    private fun text(value: String) = InlineContent.Text(value, annotations = emptyAnnotations())

    private fun emptyAnnotations() =
        xyz.robinjoon.notionblog.domain.post.block.inline
            .TextAnnotations()
}
