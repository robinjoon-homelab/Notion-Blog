package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.CHILD
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.OUTSIDE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.ROOT
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.block
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.page
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.source
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.timedSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import java.time.Duration

class NotionPostSourceTest {
    @Test
    fun `returns page with nested blocks and confirmed child pages without inlining child content`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                block("paragraph-1", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
                block("discarded", "paragraph", payload = """{"rich_text":[]}""").copy(inTrash = true),
                block(CHILD_DASHED_UPPER, "child_page", hasChildren = true, payload = """{"title":"Child"}"""),
            )
        every { client.fetchDirectBlockChildren("paragraph-1") } returns
            listOf(
                block("paragraph-2", "paragraph", payload = """{"rich_text":[]}"""),
            )
        every { client.fetchPage(CHILD) } returns page(CHILD, parent = NotionPageParentResponse("page_id", ROOT_DASHED_UPPER))

        val imported = source(client).fetch(reference(ROOT_DASHED_UPPER))

        assertThat(imported.sourceDocument).isEqualTo(reference(ROOT))
        assertThat(imported.content.roots).hasSize(2)
        assertThat(
            imported.content.roots
                .first()
                .content,
        ).isInstanceOf(TextBlockContent.Paragraph::class.java)
        assertThat(
            imported.content.roots
                .first()
                .children
                .map { it.id.value },
        ).containsExactly("paragraph-2")
        assertThat(imported.content.roots[1].content).isEqualTo(ReferenceBlockContent.ChildPost("Child", reference(CHILD)))
        assertThat(imported.content.roots[1].children).isEmpty()
        assertThat(imported.containedChildren).containsExactly(reference(CHILD))
    }

    @Test
    fun `keeps discovering structural children of a page without a public URL`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT, publicUrl = null)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                block(CHILD, "child_page", payload = """{"title":"Child"}"""),
            )
        every { client.fetchPage(CHILD) } returns page(CHILD, parent = NotionPageParentResponse("page_id", ROOT))

        val imported = source(client).fetch(reference(ROOT))

        assertThat(imported.title).isNotBlank()
        assertThat(imported.containedChildren).containsExactly(reference(CHILD))
    }

    @Test
    fun `does not treat page links or child pages with another parent as contained children`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                block("linked", "link_to_page", payload = """{"type":"page_id","page_id":"$OUTSIDE"}"""),
                block(CHILD, "child_page", payload = """{"title":"Link only"}"""),
            )
        every { client.fetchPage(CHILD) } returns page(CHILD, parent = NotionPageParentResponse("page_id", OTHER_PARENT))

        val imported = source(client).fetch(reference(ROOT))

        assertThat(imported.containedChildren).isEmpty()
        assertThat(imported.content.roots).hasSize(2)
    }

    @Test
    fun `fails the whole fetch when child parent verification cannot be completed`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                block(CHILD, "child_page", payload = """{"title":"Child"}"""),
            )
        every { client.fetchPage(CHILD) } throws SourceAccessException("not available")

        assertThatThrownBy { source(client).fetch(reference(ROOT)) }
            .isInstanceOf(SourceAccessException::class.java)
    }

    @Test
    fun `uses the tab item mapper for direct paragraph children`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(block("tab", "tab", hasChildren = true, payload = "{}"))
        every { client.fetchDirectBlockChildren("tab") } returns
            listOf(
                block(
                    "tab-title",
                    "paragraph",
                    payload = """{"rich_text":[],"icon":{"emoji":"📋"}}""",
                ),
            )

        val imported = source(client).fetch(reference(ROOT))

        val tab = imported.content.roots.single()
        assertThat(tab.content).isEqualTo(LayoutBlockContent.TabContainer)
        assertThat(tab.children.single().content).isInstanceOf(LayoutBlockContent.TabItem::class.java)
    }

    @Test
    fun `rejects source mismatch and mapping limit violations`() {
        val client = mockk<NotionApiClient>()
        assertThatThrownBy { source(client).fetch(SourceDocumentRef(SourceId("other"), ROOT)) }
            .isInstanceOf(SourceConfigurationException::class.java)

        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                block("one", "paragraph", payload = """{"rich_text":[]}"""),
                block("two", "paragraph", payload = """{"rich_text":[]}"""),
            )
        assertThatThrownBy { source(client, maxBlockCount = 1).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects nesting beyond the configured depth and total deadline`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                block("one", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren("one") } returns
            listOf(
                block("two", "paragraph", payload = """{"rich_text":[]}"""),
            )
        assertThatThrownBy { source(client, maxDepth = 1).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)

        var timestampIndex = 0
        val timestamps = longArrayOf(0, 2)
        assertThatThrownBy {
            timedSource(client, Duration.ofNanos(1), nanoTime = { timestamps[timestampIndex++] })
                .fetch(reference(ROOT))
        }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `rejects repeated block identifiers encountered through a recursive collection`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                block("repeated", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren("repeated") } returns
            listOf(
                block("repeated", "paragraph", payload = """{"rich_text":[]}"""),
            )

        assertThatThrownBy { source(client).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects malformed page ids returned by the Notion API`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page("not-a-notion-id")

        assertThatThrownBy { source(client).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    private companion object {
        const val ROOT_DASHED_UPPER = "A0B1C2D3-E4F5-6789-ABCD-EF0123456789"
        const val CHILD_DASHED_UPPER = "01234567-89AB-CDEF-0123-456789ABCDEF"
        const val OTHER_PARENT = "fedcba9876543210fedcba9876543210"
    }
}
