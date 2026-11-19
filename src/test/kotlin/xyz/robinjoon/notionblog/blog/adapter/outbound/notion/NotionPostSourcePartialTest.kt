package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.CHILD
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.OUTSIDE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.ROOT
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.block
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.page
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.source
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionFailureTranslator
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import java.util.UUID

class NotionPostSourcePartialTest {
    @ParameterizedTest
    @ValueSource(ints = [403, 404])
    fun `partial applies an access marker under the readable parent and continues at the next root`(status: Int) {
        val client = mockk<NotionApiClient>()
        val roots =
            listOf(block(CHILD, "paragraph", hasChildren = true, payload = PARAGRAPH), block(OUTSIDE, "paragraph", payload = PARAGRAPH))
        val failure = NotionFailureTranslator().httpFailure(status)
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT, any()) } returns roots
        every { client.fetchDirectBlockChildren(CHILD, any()) } throws failure

        val imported = source(client).fetch(reference(ROOT), allowPartial = true)

        assertThat(imported.content.roots.map { it.id.value }).containsExactly(CHILD, OUTSIDE)
        val parent = imported.content.roots.first()
        assertThat(parent.content).isInstanceOf(TextBlockContent.Paragraph::class.java)
        assertThat(parent.children.single().content).isEqualTo(UnsupportedBlockContent("paragraph", unavailable = true))
        assertThat(imported.warnings.single().httpStatus).isEqualTo(status)
        assertThat(imported.warnings.single().blockId).isEqualTo(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"))
        assertThatThrownBy { source(client).fetch(reference(ROOT)) }.isInstanceOf(SourceException::class.java)
    }

    @Test
    fun `partial uses the synchronized origin when the reference children are inaccessible`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT, true) } returns
            listOf(
                block(CHILD, "synced_block", hasChildren = true, payload = """{"synced_from":null}"""),
                block(OUTSIDE, "synced_block", hasChildren = true, payload = """{"synced_from":{"block_id":"$CHILD"}}"""),
            )
        every { client.fetchDirectBlockChildren(CHILD, true) } returns listOf(block("readable", "paragraph", payload = PARAGRAPH))
        every { client.fetchDirectBlockChildren(OUTSIDE, true) } throws NotionFailureTranslator().httpFailure(404)

        val imported = source(client).fetch(reference(ROOT), allowPartial = true)

        assertThat(imported.warnings).isEmpty()
        val original =
            imported.content.roots
                .first()
                .children
                .single()
        val synchronized =
            imported.content.roots
                .last()
                .children
                .single()
        assertThat(synchronized.content).isEqualTo(original.content)
        assertThat(synchronized.id.value).isEqualTo("synced:$OUTSIDE:readable")
    }

    @Test
    fun `partial marks only the failing table row and retains the next one in its place`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT, true) } returns
            listOf(
                block(
                    "table",
                    "table",
                    hasChildren = true,
                    payload = """{"table_width":1,"has_column_header":false,"has_row_header":false}""",
                ),
            )
        every { client.fetchDirectBlockChildren("table", true) } returns
            listOf(
                block(CHILD, "table_row", payload = "{}"),
                block("next-row", "table_row", payload = """{"cells":[[]]}"""),
            )

        val imported = source(client).fetch(reference(ROOT), allowPartial = true)

        val rows =
            imported.content.roots
                .single()
                .children
        assertThat(rows.map { it.id.value }).containsExactly(CHILD, "next-row")
        val marker = (rows.first().content as LayoutBlockContent.TableRow).cells.single().single() as InlineContent.Text
        assertThat(marker.text).isEqualTo("가져오지 못한 블록")
        assertThat(marker.annotations.bold).isTrue()
        assertThat(rows.last().content).isEqualTo(LayoutBlockContent.TableRow(listOf(emptyList())))
        assertThat(imported.warnings).hasSize(1)
    }

    @Test
    fun `partial still fails on block limits`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT, true) } returns
            listOf(block(CHILD, "embed", payload = "{}"), block(OUTSIDE, "paragraph", payload = PARAGRAPH))

        assertThatThrownBy { source(client, maxBlockCount = 1).fetch(reference(ROOT), allowPartial = true) }
            .isInstanceOf(SourceMappingException::class.java)
            .hasMessageContaining("maximum count")
    }

    private companion object {
        const val PARAGRAPH = """{"rich_text":[]}"""
    }
}
