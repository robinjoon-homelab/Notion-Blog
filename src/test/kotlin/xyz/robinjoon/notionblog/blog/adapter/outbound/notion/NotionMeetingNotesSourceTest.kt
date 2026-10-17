package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.ROOT
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.block
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.page
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.source
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException

class NotionMeetingNotesSourceTest {
    @Test
    fun `collects meeting summary and notes recursively`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                meetingNotesBlock(
                    id = "meeting",
                    summaryBlockId = SUMMARY,
                    notesBlockId = NOTES,
                    transcriptBlockId = TRANSCRIPT,
                ),
            )
        every { client.fetchDirectBlockChildren(SUMMARY) } returns
            listOf(
                block("summary-text", "paragraph", payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren(NOTES) } returns
            listOf(
                block("notes-container", "toggle", hasChildren = true, payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren("notes-container") } returns
            listOf(
                block("notes-detail", "paragraph", payload = """{"rich_text":[]}"""),
            )

        val imported = source(client).fetch(reference(ROOT))

        val meetingNotes = imported.content.roots.single()
        assertThat(meetingNotes.children.map { it.id.value }).containsExactly("summary-text", "notes-container")
        assertThat(meetingNotes.children[1].children.map { it.id.value }).containsExactly("notes-detail")
    }

    @Test
    fun `normalizes equivalent block id spellings while merging meeting sections and excluding transcripts`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                meetingNotesBlock(
                    id = "meeting",
                    summaryBlockId = SUMMARY_DASHED_UPPER,
                    notesBlockId = NOTES,
                    transcriptBlockId = TRANSCRIPT_DASHED_UPPER,
                ).copy(hasChildren = true),
            )
        every { client.fetchDirectBlockChildren("meeting") } returns
            listOf(
                block(SUMMARY, "toggle", hasChildren = true, payload = """{"rich_text":[]}"""),
                block(TRANSCRIPT, "paragraph", hasChildren = true, payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren(SUMMARY) } returns
            listOf(
                block("summary-text", "paragraph", payload = """{"rich_text":[]}"""),
            )
        every { client.fetchDirectBlockChildren(NOTES) } returns
            listOf(
                block("summary-text", "paragraph", payload = """{"rich_text":[]}"""),
                block("notes-text", "paragraph", payload = """{"rich_text":[]}"""),
            )

        val imported = source(client).fetch(reference(ROOT))

        val meetingNotes = imported.content.roots.single()
        assertThat(meetingNotes.children.map { it.id.value }).containsExactly(SUMMARY, "notes-text")
        assertThat(
            meetingNotes.children
                .first()
                .children
                .map { it.id.value },
        ).containsExactly("summary-text")
        assertThat(meetingNotes.children.flatMap { listOf(it.id.value) + it.children.map { child -> child.id.value } })
            .containsOnlyOnce("summary-text")
            .doesNotContain(TRANSCRIPT)
    }

    @Test
    fun `ignores absent null and transcript-aliased meeting section pointers`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
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
    }

    @Test
    fun `rejects a malformed transcript pointer`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                block(
                    "meeting",
                    "meeting_notes",
                    hasChildren = true,
                    payload = """{"title":[],"status":"notes_ready","children":{"transcript_block_id":42}}""",
                ),
            )
        every { client.fetchDirectBlockChildren("meeting") } returns
            listOf(
                block("transcript-section", "paragraph", payload = """{"rich_text":[]}"""),
            )

        assertThatThrownBy { source(client).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `applies nesting and total block limits to meeting note sections`() {
        val depthLimitedClient = mockk<NotionApiClient>()
        every { depthLimitedClient.fetchPage(ROOT) } returns page(ROOT)
        every { depthLimitedClient.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                meetingNotesBlock(id = "meeting", summaryBlockId = SUMMARY),
            )

        assertThatThrownBy { source(depthLimitedClient, maxDepth = 1).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)

        val countLimitedClient = mockk<NotionApiClient>()
        every { countLimitedClient.fetchPage(ROOT) } returns page(ROOT)
        every { countLimitedClient.fetchDirectBlockChildren(ROOT) } returns
            listOf(
                meetingNotesBlock(id = "meeting", summaryBlockId = SUMMARY),
            )
        every { countLimitedClient.fetchDirectBlockChildren(SUMMARY) } returns
            listOf(
                block("summary-text", "paragraph", payload = """{"rich_text":[]}"""),
            )

        assertThatThrownBy { source(countLimitedClient, maxBlockCount = 1).fetch(reference(ROOT)) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    private fun meetingNotesBlock(
        id: String,
        summaryBlockId: String? = null,
        notesBlockId: String? = null,
        transcriptBlockId: String? = null,
    ): NotionBlockEnvelope {
        val children =
            buildMap<String, String> {
                summaryBlockId?.let { put("summary_block_id", it) }
                notesBlockId?.let { put("notes_block_id", it) }
                transcriptBlockId?.let { put("transcript_block_id", it) }
            }.entries.joinToString(separator = ",") { (key, value) -> "\"$key\":\"$value\"" }
        return block(
            id = id,
            type = "meeting_notes",
            payload = """{"title":[],"status":"notes_ready","children":{$children}}""",
        )
    }

    private companion object {
        const val SUMMARY = "11111111222233334444555555555555"
        const val SUMMARY_DASHED_UPPER = "11111111-2222-3333-4444-555555555555"
        const val NOTES = "22222222333344445555666666666666"
        const val TRANSCRIPT = "66666666777788889999aaaaaaaaaaaa"
        const val TRANSCRIPT_DASHED_UPPER = "66666666-7777-8888-9999-AAAAAAAAAAAA"
        const val PRIVATE_TRANSCRIPT = "bbbbbbbbccccddddeeeeffffffffffff"
    }
}
