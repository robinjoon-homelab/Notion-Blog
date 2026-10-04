package xyz.robinjoon.notionblog.adapter.output.notion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.time.Duration

class NotionPostSourceTest {
    private val sourceId = SourceId("notion-main")
    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `returns published page with nested blocks and confirmed child pages without inlining child content`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            block("paragraph-1", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
            block("discarded", "paragraph", payload = """{"rich_text":[]}""", inTrash = true),
            block(CHILD_DASHED_UPPER, "child_page", hasChildren = true, payload = """{"title":"Child"}"""),
        )
        every { client.fetchDirectBlockChildren("paragraph-1") } returns listOf(
            block("paragraph-2", "paragraph", payload = """{"rich_text":[]}"""),
        )
        every { client.fetchPage(CHILD) } returns page(CHILD, parent = NotionPageParentResponse("page_id", ROOT_DASHED_UPPER))

        val imported = source(client).fetch(reference(ROOT_DASHED_UPPER))

        assertThat(imported.sourceDocument).isEqualTo(reference(ROOT))
        assertThat(imported.publicationStatus).isEqualTo(ImportedPublicationStatus.PUBLISHED)
        assertThat(imported.content.roots).hasSize(2)
        assertThat(imported.content.roots.first().content).isInstanceOf(TextBlockContent.Paragraph::class.java)
        assertThat(imported.content.roots.first().children.map { it.id.value }).containsExactly("paragraph-2")
        assertThat(imported.content.roots[1].content).isEqualTo(ReferenceBlockContent.ChildPost("Child", reference(CHILD)))
        assertThat(imported.content.roots[1].children).isEmpty()
        assertThat(imported.containedChildren).containsExactly(reference(CHILD))
        verify(exactly = 1) { client.fetchDirectBlockChildren(ROOT) }
        verify(exactly = 0) { client.fetchDirectBlockChildren(CHILD) }
    }

    @Test
    fun `keeps discovering structural children of an unpublished parent`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT, publicUrl = null)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            block(CHILD, "child_page", payload = """{"title":"Child"}"""),
        )
        every { client.fetchPage(CHILD) } returns page(CHILD, parent = NotionPageParentResponse("page_id", ROOT))

        val imported = source(client).fetch(reference(ROOT))

        assertThat(imported.publicationStatus).isEqualTo(ImportedPublicationStatus.UNPUBLISHED)
        assertThat(imported.containedChildren).containsExactly(reference(CHILD))
    }

    @Test
    fun `does not treat page links or child pages with another parent as contained children`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            block("linked", "link_to_page", payload = """{"type":"page_id","page_id":"$OUTSIDE"}"""),
            block(CHILD, "child_page", payload = """{"title":"Link only"}"""),
        )
        every { client.fetchPage(CHILD) } returns page(CHILD, parent = NotionPageParentResponse("page_id", OTHER_PARENT))

        val imported = source(client).fetch(reference(ROOT))

        assertThat(imported.containedChildren).isEmpty()
        assertThat(imported.content.roots).hasSize(2)
        verify(exactly = 0) { client.fetchPage(OUTSIDE) }
    }

    @Test
    fun `fails the whole fetch when child parent verification cannot be completed`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
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
        every { client.fetchDirectBlockChildren("tab") } returns listOf(
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
    fun `copies collected origin children with stable reference scoped ids when direct children are empty`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
            synchronizedBlock(SECOND_SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
            synchronizedBlock(SYNCED_ORIGIN, hasChildren = true),
        )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns listOf(
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
        assertThat(secondImport.content.roots[0].children.map { it.id.value })
            .containsExactlyElementsOf(synchronizedReference.children.map { it.id.value })
        verify(exactly = 2) { client.fetchDirectBlockChildren(SYNCED_ORIGIN) }
        verify(exactly = 2) { client.fetchDirectBlockChildren(SYNCED_REFERENCE) }
        verify(exactly = 2) { client.fetchDirectBlockChildren(SECOND_SYNCED_REFERENCE) }
    }

    @Test
    fun `does not fetch a synchronized origin when the reference supplies direct children`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
        )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns listOf(
            block("reference-child", "paragraph", payload = """{"rich_text":[]}"""),
        )

        val imported = source(client).fetch(reference(ROOT))

        assertThat(imported.content.roots.single().children.map { it.id.value }).containsExactly("reference-child")
        verify(exactly = 0) { client.fetchDirectBlockChildren(SYNCED_ORIGIN) }
    }

    @Test
    fun `does not fetch a synchronized origin when all direct database children are intentionally excluded`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
        )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns listOf(
            block(CHILD, "child_database", hasChildren = true, payload = """{"title":"Excluded database"}"""),
        )
        every { client.fetchDatabase(CHILD) } returns NotionDatabaseResponse(CHILD, "Excluded database", null, false, NotionParentResponse.Workspace, emptyList())
        every { client.fetchDatabaseViews(CHILD, null) } returns NotionPaginationResponse(listOf(OUTSIDE), false, null)
        every { client.fetchDatabaseView(OUTSIDE) } returns NotionDatabaseViewResponse(OUTSIDE, CHILD, "Board", "board", null, null)
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } throws SourceAccessException("origin must not be read")

        val imported = source(client).fetch(reference(ROOT))

        assertThat(imported.content.roots.single().children).isEmpty()
        verify(exactly = 0) { client.fetchDirectBlockChildren(SYNCED_ORIGIN) }
        verify(exactly = 0) { client.createViewQuery(any()) }
    }

    @Test
    fun `applies depth limits when reusing synchronized origin children below a deeper reference`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            synchronizedBlock(SYNCED_ORIGIN, hasChildren = true),
            block("container", "toggle", hasChildren = true, payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns listOf(
            block("origin-child", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren("origin-child") } returns listOf(
            block("origin-grandchild", "paragraph", payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren("container") } returns listOf(
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
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            synchronizedBlock(SYNCED_ORIGIN, hasChildren = true),
            synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
            block(CHILD, "child_page", payload = """{"title":"Sentinel"}"""),
        )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns listOf(
            block("origin-child", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren("origin-child") } returns listOf(
            block("origin-grandchild", "paragraph", payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } returns emptyList()
        every { client.fetchPage(CHILD) } returns page(CHILD, parent = NotionPageParentResponse("page_id", ROOT))

        assertThatThrownBy { source(client, maxBlockCount = 5).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.fetchPage(CHILD) }
    }

    @Test
    fun `checks the collection deadline before copying cached synchronized children`() {
        val client = mockk<NotionApiClient>()
        var referenceChildrenFetched = false
        var deadlineChecksAfterReferenceFetch = 0
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            synchronizedBlock(SYNCED_ORIGIN, hasChildren = true),
            synchronizedBlock(SYNCED_REFERENCE, hasChildren = true, originBlockId = SYNCED_ORIGIN),
        )
        every { client.fetchDirectBlockChildren(SYNCED_ORIGIN) } returns listOf(
            block("shared-child", "paragraph", payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren(SYNCED_REFERENCE) } answers {
            referenceChildrenFetched = true
            emptyList()
        }

        assertThatThrownBy {
            source(
                client = client,
                collectionTimeout = Duration.ofNanos(1),
                nanoTime = {
                    if (!referenceChildrenFetched || deadlineChecksAfterReferenceFetch++ == 0) 0 else 1
                },
            ).fetch(reference(ROOT))
        }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `collects meeting summary and notes recursively without fetching the transcript`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            meetingNotesBlock(
                id = "meeting",
                summaryBlockId = SUMMARY,
                notesBlockId = NOTES,
                transcriptBlockId = TRANSCRIPT,
            ),
        )
        every { client.fetchDirectBlockChildren(SUMMARY) } returns listOf(
            block("summary-text", "paragraph", payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren(NOTES) } returns listOf(
            block("notes-container", "toggle", hasChildren = true, payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren("notes-container") } returns listOf(
            block("notes-detail", "paragraph", payload = """{"rich_text":[]}"""),
        )

        val imported = source(client).fetch(reference(ROOT))

        val meetingNotes = imported.content.roots.single()
        assertThat(meetingNotes.children.map { it.id.value }).containsExactly("summary-text", "notes-container")
        assertThat(meetingNotes.children[1].children.map { it.id.value }).containsExactly("notes-detail")
        verify(exactly = 1) { client.fetchDirectBlockChildren(SUMMARY) }
        verify(exactly = 1) { client.fetchDirectBlockChildren(NOTES) }
        verify(exactly = 0) { client.fetchDirectBlockChildren(TRANSCRIPT) }
    }

    @Test
    fun `normalizes equivalent block id spellings while merging meeting sections and excluding transcripts`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            meetingNotesBlock(
                id = "meeting",
                hasChildren = true,
                summaryBlockId = SUMMARY_DASHED_UPPER,
                notesBlockId = NOTES,
                transcriptBlockId = TRANSCRIPT_DASHED_UPPER,
            ),
        )
        every { client.fetchDirectBlockChildren("meeting") } returns listOf(
            block(SUMMARY, "toggle", hasChildren = true, payload = """{"rich_text":[]}"""),
            block(TRANSCRIPT, "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren(SUMMARY) } returns listOf(
            block("summary-text", "paragraph", payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren(NOTES) } returns listOf(
            block("summary-text", "paragraph", payload = """{"rich_text":[]}"""),
            block("notes-text", "paragraph", payload = """{"rich_text":[]}"""),
        )

        val imported = source(client).fetch(reference(ROOT))

        val meetingNotes = imported.content.roots.single()
        assertThat(meetingNotes.children.map { it.id.value }).containsExactly(SUMMARY, "notes-text")
        assertThat(meetingNotes.children.first().children.map { it.id.value }).containsExactly("summary-text")
        assertThat(meetingNotes.children.flatMap { listOf(it.id.value) + it.children.map { child -> child.id.value } })
            .containsOnlyOnce("summary-text")
            .doesNotContain(TRANSCRIPT)
        verify(exactly = 1) { client.fetchDirectBlockChildren(SUMMARY) }
        verify(exactly = 0) { client.fetchDirectBlockChildren(SUMMARY_DASHED_UPPER) }
        verify(exactly = 1) { client.fetchDirectBlockChildren(NOTES) }
        verify(exactly = 0) { client.fetchDirectBlockChildren(TRANSCRIPT) }
        verify(exactly = 0) { client.fetchDirectBlockChildren(TRANSCRIPT_DASHED_UPPER) }
    }

    @Test
    fun `ignores absent null and transcript-aliased meeting section pointers`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            block("missing", "meeting_notes", payload = """{"title":[],"status":"notes_ready"}"""),
            block("null", "meeting_notes", payload = """{"title":[],"status":"notes_ready","children":null}"""),
            meetingNotesBlock(
                id = "aliased",
                summaryBlockId = PRIVATE_TRANSCRIPT,
                notesBlockId = PRIVATE_TRANSCRIPT,
                transcriptBlockId = PRIVATE_TRANSCRIPT,
            ),
        )

        val imported = source(client).fetch(reference(ROOT))

        assertThat(imported.content.roots).allSatisfy { block -> assertThat(block.children).isEmpty() }
        verify(exactly = 0) { client.fetchDirectBlockChildren(PRIVATE_TRANSCRIPT) }
    }

    @Test
    fun `rejects a malformed transcript pointer before collecting ordinary meeting children`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            block(
                "meeting",
                "meeting_notes",
                hasChildren = true,
                payload = """{"title":[],"status":"notes_ready","children":{"transcript_block_id":42}}""",
            ),
        )
        every { client.fetchDirectBlockChildren("meeting") } returns listOf(
            block("transcript-section", "paragraph", payload = """{"rich_text":[]}"""),
        )

        assertThatThrownBy { source(client).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { client.fetchDirectBlockChildren("meeting") }
    }

    @Test
    fun `applies nesting and total block limits to meeting note sections`() {
        val depthLimitedClient = mockk<NotionApiClient>()
        every { depthLimitedClient.fetchPage(ROOT) } returns page(ROOT)
        every { depthLimitedClient.fetchDirectBlockChildren(ROOT) } returns listOf(
            meetingNotesBlock(id = "meeting", summaryBlockId = SUMMARY),
        )

        assertThatThrownBy { source(depthLimitedClient, maxDepth = 1).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
        verify(exactly = 0) { depthLimitedClient.fetchDirectBlockChildren(SUMMARY) }

        val countLimitedClient = mockk<NotionApiClient>()
        every { countLimitedClient.fetchPage(ROOT) } returns page(ROOT)
        every { countLimitedClient.fetchDirectBlockChildren(ROOT) } returns listOf(
            meetingNotesBlock(id = "meeting", summaryBlockId = SUMMARY),
        )
        every { countLimitedClient.fetchDirectBlockChildren(SUMMARY) } returns listOf(
            block("summary-text", "paragraph", payload = """{"rich_text":[]}"""),
        )

        assertThatThrownBy { source(countLimitedClient, maxBlockCount = 1).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects source mismatch before fetching and mapping limit violations`() {
        val client = mockk<NotionApiClient>()
        assertThatThrownBy { source(client).fetch(SourceDocumentRef(SourceId("other"), ROOT)) }
            .isInstanceOf(SourceConfigurationException::class.java)
        verify(exactly = 0) { client.fetchPage(any()) }

        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
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
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            block("one", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren("one") } returns listOf(
            block("two", "paragraph", payload = """{"rich_text":[]}"""),
        )
        assertThatThrownBy { source(client, maxDepth = 1).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)

        var timestampIndex = 0
        val timestamps = longArrayOf(0, 2)
        assertThatThrownBy {
            source(client, collectionTimeout = Duration.ofNanos(1), nanoTime = { timestamps[timestampIndex++] })
                .fetch(reference(ROOT))
        }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `rejects repeated block identifiers encountered through a recursive collection`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(
            block("repeated", "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
        )
        every { client.fetchDirectBlockChildren("repeated") } returns listOf(
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

    private fun source(
        client: NotionApiClient,
        maxDepth: Int = 8,
        maxBlockCount: Int = 100,
        collectionTimeout: Duration = Duration.ofSeconds(1),
        nanoTime: () -> Long = System::nanoTime,
    ) = NotionPostSource(
        sourceId = sourceId,
        client = client,
        maxDepth = maxDepth,
        maxBlockCount = maxBlockCount,
        collectionTimeout = collectionTimeout,
        nanoTime = nanoTime,
    )

    private fun reference(externalId: String) = SourceDocumentRef(sourceId, externalId)

    private fun page(
        id: String,
        parent: NotionPageParentResponse = NotionPageParentResponse("workspace", null),
        publicUrl: String? = "https://workspace.notion.site/$id",
    ) = NotionPageResponse(
        id = id,
        parent = parent,
        url = "https://www.notion.so/$id",
        publicUrl = publicUrl,
        inTrash = false,
        lastEditedTime = "2026-08-25T00:00:00Z",
        properties = objectMapper.readTree("""{"Name":{"type":"title","title":[{"plain_text":"$id"}]}}"""),
    )

    private fun block(
        id: String,
        type: String,
        hasChildren: Boolean = false,
        payload: String,
        inTrash: Boolean = false,
    ) = NotionBlockEnvelope(id, type, hasChildren, inTrash, objectMapper.readTree(payload))

    private fun meetingNotesBlock(
        id: String,
        hasChildren: Boolean = false,
        summaryBlockId: String? = null,
        notesBlockId: String? = null,
        transcriptBlockId: String? = null,
    ): NotionBlockEnvelope {
        val children = buildMap<String, String> {
            summaryBlockId?.let { put("summary_block_id", it) }
            notesBlockId?.let { put("notes_block_id", it) }
            transcriptBlockId?.let { put("transcript_block_id", it) }
        }.entries.joinToString(separator = ",") { (key, value) -> "\"$key\":\"$value\"" }
        return block(
            id = id,
            type = "meeting_notes",
            hasChildren = hasChildren,
            payload = """{"title":[],"status":"notes_ready","children":{$children}}""",
        )
    }

    private fun synchronizedBlock(
        id: String,
        hasChildren: Boolean,
        originBlockId: String? = null,
    ) = block(
        id = id,
        type = "synced_block",
        hasChildren = hasChildren,
        payload = originBlockId
            ?.let { """{"synced_from":{"type":"block_id","block_id":"$it"}}""" }
            ?: """{"synced_from":null}""",
    )

    private companion object {
        const val ROOT = "a0b1c2d3e4f56789abcdef0123456789"
        const val ROOT_DASHED_UPPER = "A0B1C2D3-E4F5-6789-ABCD-EF0123456789"
        const val CHILD = "0123456789abcdef0123456789abcdef"
        const val CHILD_DASHED_UPPER = "01234567-89AB-CDEF-0123-456789ABCDEF"
        const val SUMMARY = "11111111222233334444555555555555"
        const val SUMMARY_DASHED_UPPER = "11111111-2222-3333-4444-555555555555"
        const val NOTES = "22222222333344445555666666666666"
        const val TRANSCRIPT = "66666666777788889999aaaaaaaaaaaa"
        const val TRANSCRIPT_DASHED_UPPER = "66666666-7777-8888-9999-AAAAAAAAAAAA"
        const val PRIVATE_TRANSCRIPT = "bbbbbbbbccccddddeeeeffffffffffff"
        const val SYNCED_ORIGIN = "ccccccccddddeeeeffff000000000000"
        const val SYNCED_REFERENCE = "ddddddddeeeeffff0000111111111111"
        const val SECOND_SYNCED_REFERENCE = "eeeeeeeeffff00001111222222222222"
        const val OTHER_PARENT = "fedcba9876543210fedcba9876543210"
        const val OUTSIDE = "1234567890abcdef1234567890abcdef"
    }
}
