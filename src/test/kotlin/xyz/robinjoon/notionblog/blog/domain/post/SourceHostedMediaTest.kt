package xyz.robinjoon.notionblog.blog.domain.post

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.net.URI

class SourceHostedMediaTest {
    @Test
    fun `collects every source-hosted file shown anywhere in the tree`() {
        val image = PostFixtures.hosted("image.png")
        val calloutIcon = PostFixtures.hosted("callout.png")
        val tabIcon = PostFixtures.hosted("tab.png")
        val rowIcon = PostFixtures.hosted("row-icon.png")
        val rowCover = PostFixtures.hosted("row-cover.png")
        val tree =
            BlockTree(
                listOf(
                    BlockNode(
                        BlockId("callout"),
                        TextBlockContent.Callout(listOf(InlineContent.Text("note")), BlockIcon.Media(calloutIcon)),
                        children = listOf(PostFixtures.image("nested-image", image)),
                    ),
                    BlockNode(BlockId("tab"), LayoutBlockContent.TabItem(emptyList(), BlockIcon.Media(tabIcon))),
                    BlockNode(
                        BlockId("gallery"),
                        DataViewContent.Gallery(dataSet(DataRow(listOf(emptyList()), null, BlockIcon.Media(rowIcon), rowCover))),
                    ),
                    PostFixtures.image("external", MediaSource.External(URI("https://example.com/external.png"))),
                ),
            )

        assertThat(tree.sourceHostedMedia()).containsExactlyInAnyOrder(image, calloutIcon, tabIcon, rowIcon, rowCover)
    }

    @Test
    fun `source media keys drop only the signature query and fragment`() {
        val first = SourceMediaKey.of(URI("https://files.example.com/a/b.png?X-Amz-Signature=1#frag"))
        val second = SourceMediaKey.of(URI("https://files.example.com/a/b.png?X-Amz-Signature=2"))

        assertThat(first).isEqualTo(second).isEqualTo(SourceMediaKey("https://files.example.com/a/b.png"))
    }

    private fun dataSet(row: DataRow) = DataSet("Gallery", listOf(DataColumn("Name")), listOf(row))
}
