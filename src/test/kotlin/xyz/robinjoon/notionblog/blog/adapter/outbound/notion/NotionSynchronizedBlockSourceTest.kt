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
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import java.time.Duration

class NotionSynchronizedBlockSourceTest {
    @Test
    fun `copies collected origin children with stable reference scoped ids when direct children are empty`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
                synchronizedBlock(SECOND_SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
                synchronizedBlock(SYNCED_ORIGIN, hasChildren = true),
            )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns
            listOf(
                block("shared-child", "paragraph", payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns emptyList()
        every { client.fetchDirectBlockChildren(SECOND_SYNCED_REFERENCE) } returns emptyList()

        val firstImport = source(client).fetch(reference(ROOT))
        val secondImport = source(client).fetch(reference(ROOT))

        val synchronizedReference = firstImport.content.roots[0]
        val secondSynchronizedReference = firstImport.content.roots[1]
        val origin = firstImport.content.roots[2]
        assertThat(origin.children.map { it.id.value }).containsExactly("shared-child")
        assertThat(synchronizedReference.children.map { it.id.value })
            .containsExactly("synced:$SYNCED_REFERENCE:shared-child")
        assertThat(secondSynchronizedReference.children.map { it.id.value })
            .containsExactly("synced:$SECOND_SYNCED_REFERENCE:shared-child")
        assertThat(synchronizedReference.children.single().content).isSameAs(origin.children.single().content)
        assertThat(
            secondImport.content.roots[0]
                .children
                .map { it.id.value },
        ).containsExactlyElementsOf(synchronizedReference.children.map { it.id.value })
    }

    @Test
    fun `uses direct children supplied by a synchronized reference`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
            )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns
            listOf(
                block("reference-child", "paragraph", payload = """{"rich_text":[]}"""),
            )

        val imported = source(client).fetch(reference(ROOT))

        assertThat(
            imported.content.roots
                .single()
                .children
                .map { it.id.value },
        ).containsExactly("reference-child")
    }

    @Test
    fun `does not copy synchronized origin children when all direct database children are intentionally excluded`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
            )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns
            listOf(
                block(CHILD, "child_database", hasChildren = true, payload = """{"title":"Excluded database"}"""),
            )
        every { client.fetchDatabase(CHILD) } returns
            NotionDatabaseResponse(CHILD, "Excluded database", null, false, NotionParentResponse.Workspace, emptyList())
        every { client.fetchDatabaseViews(CHILD, null) } returns NotionPaginationResponse(listOf(OUTSIDE), false, null)
        every { client.fetchDatabaseView(OUTSIDE) } returns NotionDatabaseViewResponse(OUTSIDE, CHILD, "Board", "board", null, null)
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns
            listOf(
                block("origin-child", "paragraph", payload = """{"rich_text":[]}"""),
            )

        val imported = source(client).fetch(reference(ROOT))

        assertThat(
            imported.content.roots
                .single()
                .children,
        ).isEmpty()
    }

    @Test
    fun `applies depth limits when reusing synchronized origin children below a deeper reference`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                synchronizedBlock(SYNCED_ORIGIN, hasChildren = true),
                block("container", "toggle", hasChildren = true, payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns
            listOf(
                block("origin-child", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren("origin-child") } returns
            listOf(
                block("origin-grandchild", "paragraph", payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren("container") } returns
            listOf(
                synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
            )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns emptyList()

        assertThatThrownBy { source(client, maxDepth = 3).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `applies total block limits to synchronized reference copies`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                synchronizedBlock(SYNCED_ORIGIN, hasChildren = true),
                synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
                block(CHILD, "child_page", payload = """{"title":"Sentinel"}"""),
            )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns
            listOf(
                block("origin-child", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren("origin-child") } returns
            listOf(
                block("origin-grandchild", "paragraph", payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns emptyList()
        every { client.fetchPage(CHILD) } returns page(CHILD, parent = NotionPageParentResponse("page_id", ROOT))

        assertThatThrownBy { source(client, maxBlockCount = 5).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `checks the collection deadline before copying cached synchronized children`() {
        val client = mockk<NotionApiClient>()
        var referenceChildrenFetched = false
        var deadlineChecksAfterReferenceFetch = 0
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                synchronizedBlock(SYNCED_ORIGIN, hasChildren = true),
                synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
            )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns
            listOf(
                block("shared-child", "paragraph", payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } answers {
            referenceChildrenFetched = true
            emptyList()
        }

        assertThatThrownBy {
            timedSource(
                client = client,
                collectionTimeout = Duration.ofNanos(1),
                nanoTime = {
                    if (!referenceChildrenFetched || deadlineChecksAfterReferenceFetch++ == 0) 0 else 1
                },
            ).fetch(reference(ROOT))
        }.isInstanceOf(RetryableSourceException::class.java)
    }

    private fun synchronizedBlock(
        id: String,
        hasChildren: Boolean,
        originBlockId: String? = null,
    ) = block(
        id = id,
        type = "synced_block",
        hasChildren = hasChildren,
        payload =
            originBlockId
                ?.let { """{"synced_from":{"type":"block_id","block_id":"$it"}}""" }
                ?: """{"synced_from":null}""",
    )

    private companion object {
        const val SYNCED_ORIGIN = "ccccccccddddeeeeffff000000000000"
        const val SYNCED_REFERENCE = "ddddddddeeeeffff0000111111111111"
        const val SECOND_SYNCED_REFERENCE = "eeeeeeeeffff00001111222222222222"
    }
}
