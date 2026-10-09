package xyz.robinjoon.notionblog.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.application.model.LinkResolution
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.util.UUID

class ResolvePostLinksServiceTest {
    private val postRepository = mockk<PostRepository>()
    private val publicationRepository = mockk<PublicationRepository>()
    private val service = ResolvePostLinksService(postRepository, publicationRepository)
    private val publicationId = PublicationId(UUID.randomUUID())

    @Test
    fun `resolves cell and row links consistently across table list and gallery without creating publication members`() {
        val reference = sourceDocument("cell")
        val target = LinkTarget.SourceDocument(reference, URI("https://example.com/cell"))
        val rowReference = sourceDocument("row")
        val rowTarget = LinkTarget.SourceDocument(rowReference, URI("https://example.com/row"))
        val postId = PostId(UUID.randomUUID())
        every { postRepository.findBindingsBySourceDocuments(any()) } returns mapOf(reference to PostSourceBinding(postId, reference))
        every { publicationRepository.findActiveMemberPostIds(publicationId, setOf(postId)) } returns setOf(postId)
        val data =
            DataSet(
                "View",
                listOf(DataColumn("Name")),
                listOf(DataRow(listOf(listOf(InlineContent.Text("Cell", link = target))), link = rowTarget)),
            )
        val views = listOf(DataViewContent.Table(data), DataViewContent.ListView(data), DataViewContent.Gallery(data))

        views.forEachIndexed { index, view ->
            val tree = BlockTree(listOf(BlockNode(BlockId("view-$index"), view)))
            assertThat(service.resolve(publicationId, listOf(tree)))
                .containsEntry(target, LinkResolution.Internal(postId))
                .containsEntry(rowTarget, LinkResolution.External(URI("https://example.com/row")))
        }
    }

    @Test
    fun `resolves active member links internally without consulting availability`() {
        val reference = sourceDocument("active")
        val target = LinkTarget.SourceDocument(reference, URI("https://notion.so/active"))
        val targetPostId = PostId(UUID.randomUUID())
        every { postRepository.findBindingsBySourceDocuments(setOf(reference)) } returns
            mapOf(reference to PostSourceBinding(targetPostId, reference))
        every { publicationRepository.findActiveMemberPostIds(publicationId, setOf(targetPostId)) } returns setOf(targetPostId)

        assertThat(service.resolve(publicationId, listOf(tree(target))))
            .containsEntry(target, LinkResolution.Internal(targetPostId))
        verify(exactly = 0) { postRepository.findAvailability(any()) }
        verify(exactly = 0) { postRepository.findAvailabilities(any()) }
    }

    @Test
    fun `keeps safe original url external when a bound target is outside active scope`() {
        val reference = sourceDocument("outside")
        val target = LinkTarget.SourceDocument(reference, URI("https://example.com/original"))
        val targetPostId = PostId(UUID.randomUUID())
        every { postRepository.findBindingsBySourceDocuments(setOf(reference)) } returns
            mapOf(reference to PostSourceBinding(targetPostId, reference))
        every { publicationRepository.findActiveMemberPostIds(publicationId, setOf(targetPostId)) } returns emptySet()

        assertThat(service.resolve(publicationId, listOf(tree(target))))
            .containsEntry(target, LinkResolution.External(URI("https://example.com/original")))
    }

    @Test
    fun `does not generate a link when target cannot be safely resolved`() {
        val reference = sourceDocument("unresolved")
        val target = LinkTarget.SourceDocument(reference, URI("javascript:alert(1)"))
        every { postRepository.findBindingsBySourceDocuments(setOf(reference)) } returns emptyMap()
        every { publicationRepository.findActiveMemberPostIds(publicationId, emptySet()) } returns emptySet()

        assertThat(service.resolve(publicationId, listOf(tree(target))))
            .containsEntry(target, LinkResolution.Unlinked)
    }

    @Test
    fun `collects nested inline and reference links before performing one batch lookup per repository`() {
        val inlineReference = sourceDocument("inline")
        val referenceBlockDocument = sourceDocument("reference-block")
        val inlineTarget = LinkTarget.SourceDocument(inlineReference, URI("https://example.com/inline"))
        val referenceTarget = LinkTarget.SourceDocument(referenceBlockDocument, URI("https://example.com/reference"))
        val inlinePostId = PostId(UUID.randomUUID())
        val referencePostId = PostId(UUID.randomUUID())
        val sourceDocuments = setOf(inlineReference, referenceBlockDocument)
        val postIds = setOf(inlinePostId, referencePostId)
        val tree =
            BlockTree(
                listOf(
                    BlockNode(
                        BlockId("root"),
                        TextBlockContent.Paragraph(listOf(InlineContent.Text("linked", link = inlineTarget))),
                        children =
                            listOf(
                                BlockNode(
                                    BlockId("reference"),
                                    ReferenceBlockContent.DocumentLink(referenceBlockDocument, referenceTarget.originalUrl),
                                ),
                            ),
                    ),
                ),
            )
        every { postRepository.findBindingsBySourceDocuments(sourceDocuments) } returns
            mapOf(
                inlineReference to PostSourceBinding(inlinePostId, inlineReference),
                referenceBlockDocument to PostSourceBinding(referencePostId, referenceBlockDocument),
            )
        every { publicationRepository.findActiveMemberPostIds(publicationId, postIds) } returns postIds

        assertThat(service.resolve(publicationId, listOf(tree))).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                inlineTarget to LinkResolution.Internal(inlinePostId),
                referenceTarget to LinkResolution.Internal(referencePostId),
            ),
        )
        verify(exactly = 1) { postRepository.findBindingsBySourceDocuments(sourceDocuments) }
        verify(exactly = 1) { publicationRepository.findActiveMemberPostIds(publicationId, postIds) }
    }

    private fun tree(target: LinkTarget.SourceDocument): BlockTree =
        BlockTree(
            listOf(BlockNode(BlockId("root"), TextBlockContent.Paragraph(listOf(InlineContent.Text("linked", link = target))))),
        )

    private fun sourceDocument(externalId: String): SourceDocumentRef = SourceDocumentRef(SourceId("notion"), externalId)
}
