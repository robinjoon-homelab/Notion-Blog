package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.assembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.clock
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ColumnListView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MediaView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.TabContainerView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.TableView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import java.net.URI
import java.util.UUID

class PostPageStoredMediaViewTest {
    @Test
    fun `renders nested source hosted media inside layout containers`() {
        val source = MediaSource.SourceHosted(URI("https://example.com/nested.png"), clock.instant().plusSeconds(60))
        val image = node("image", MediaBlockContent.Media(MediaType.IMAGE, source, "nested.png"))
        val row =
            BlockNode(
                BlockId("row"),
                LayoutBlockContent.TableRow(listOf(listOf(InlineContent.Text("Cell")))),
                children = listOf(image),
            )
        val table = BlockNode(BlockId("table"), LayoutBlockContent.Table(1), children = listOf(row))
        val tab = BlockNode(BlockId("tab"), LayoutBlockContent.TabItem(listOf(InlineContent.Text("Tab")), null), children = listOf(table))
        val tabs = BlockNode(BlockId("tabs"), LayoutBlockContent.TabContainer, children = listOf(tab))
        val column = BlockNode(BlockId("column"), LayoutBlockContent.Column(null), children = listOf(tabs))
        val columns = BlockNode(BlockId("columns"), LayoutBlockContent.ColumnList, children = listOf(column))

        val storedKey = MediaKey("d".repeat(64))
        val references =
            ContentReferences(
                emptyMap(),
                RevisionMedia(mapOf(WebFixtures.revisionId to mapOf(SourceMediaKey.of(source.url) to storedKey))),
            )

        val view = assembler.assemble(WebFixtures.publicPage(WebFixtures.revision(nodes = listOf(columns)), references))
        val columnView = (view.post.blocks.single() as ColumnListView).columns.single()
        val tabView = (columnView.children.single() as TabContainerView).tabs.single()
        val rowView = (tabView.children.single() as TableView).rows.single()

        assertThat((rowView.children.single() as MediaView).url).isEqualTo("/media/${storedKey.value}")
    }

    @Test
    fun `renders each revision's own stored file when article header and preview share one source file`() {
        val source = MediaSource.SourceHosted(URI("https://files.notion.so/shared.png"), null)
        val sourceKey = SourceMediaKey.of(source.url)
        val articleRevisionId = PostRevisionId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
        val headerRevisionId = PostRevisionId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
        val articleKey = MediaKey("a".repeat(64))
        val headerKey = MediaKey("b".repeat(64))
        val image = node("image", MediaBlockContent.Media(MediaType.IMAGE, source, "shared.png"))
        val article =
            WebFixtures
                .revision(
                    nodes = listOf(image),
                    properties = PostProperties("Post", null, emptyList(), source),
                ).copy(id = articleRevisionId)
        val header = WebFixtures.revision(title = "Header", nodes = listOf(image)).copy(id = headerRevisionId)
        val references =
            ContentReferences(
                emptyMap(),
                RevisionMedia(
                    mapOf(
                        articleRevisionId to mapOf(sourceKey to articleKey),
                        headerRevisionId to mapOf(sourceKey to headerKey),
                    ),
                ),
            )

        val view = assembler.assemble(WebFixtures.publicPage(article, references, WebFixtures.chrome(header = header)))

        assertThat((view.post.blocks.single() as MediaView).url).isEqualTo("/media/${articleKey.value}")
        assertThat(view.properties.coverHref).isEqualTo("/media/${articleKey.value}")
        val headerImage = checkNotNull(view.chrome.header).blocks.single() as MediaView
        assertThat(headerImage.url).isEqualTo("/media/${headerKey.value}")
    }

    @Test
    fun `does not borrow another revision's stored file for a file it has no copy of`() {
        val source = MediaSource.SourceHosted(URI("https://files.notion.so/other.png"), clock.instant().minusSeconds(1))
        val otherRevisionId = PostRevisionId(UUID.fromString("00000000-0000-0000-0000-0000000000b3"))
        val references =
            ContentReferences(
                emptyMap(),
                RevisionMedia(mapOf(otherRevisionId to mapOf(SourceMediaKey.of(source.url) to MediaKey("c".repeat(64))))),
            )
        val revision = WebFixtures.revision(nodes = listOf(node("image", MediaBlockContent.Media(MediaType.IMAGE, source, "other.png"))))

        val view = assembler.assemble(WebFixtures.publicPage(revision, references))

        assertThat((view.post.blocks.single() as MediaView).url).isNull()
    }

    @Test
    fun `never exposes the signed source URL of a file without a stored copy in public or preview pages`() {
        val source =
            MediaSource.SourceHosted(
                URI("https://files.notion.so/signed.png?X-Amz-Signature=secret"),
                clock.instant().plusSeconds(3600),
            )
        val revision =
            WebFixtures.revision(
                nodes = listOf(node("image", MediaBlockContent.Media(MediaType.IMAGE, source, "signed.png"))),
                properties = PostProperties("Post", null, emptyList(), source),
            )

        val publicView = assembler.assemble(WebFixtures.publicPage(revision))
        val previewView =
            assembler.assemble(
                AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()).copy(revision = revision),
            )

        listOf(publicView, previewView).forEach { view ->
            assertThat((view.post.blocks.single() as MediaView).url).isNull()
            assertThat(view.properties.coverHref).isNull()
        }
    }
}
