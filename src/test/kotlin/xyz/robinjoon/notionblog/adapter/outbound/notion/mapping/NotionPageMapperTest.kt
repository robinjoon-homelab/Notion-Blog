package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId

class NotionPageMapperTest {
    private val objectMapper = JsonMapper.builder().build()
    private val mapper = NotionPageMapper()

    @Test
    fun `normalizes a page title and source revision`() {
        val mapped =
            mapper.map(
                page(
                    id = "A0B1C2D3-E4F5-6789-ABCD-EF0123456789",
                    properties = """{"Name":{"type":"title","title":[{"plain_text":"  A   title  "}]}}""",
                    lastEditedTime = "2026-08-25T09:00:00+09:00",
                ),
                reference("a0b1c2d3e4f56789abcdef0123456789"),
            )

        assertThat(mapped.sourceDocument).isEqualTo(reference("a0b1c2d3e4f56789abcdef0123456789"))
        assertThat(mapped.title).isEqualTo("A title")
        assertThat(mapped.sourceRevision.value).isEqualTo("2026-08-25T00:00:00Z")
        assertThat(mapped.publicationStatus).isEqualTo(ImportedPublicationStatus.PUBLISHED)
    }

    @Test
    fun `treats trashed and non-public pages as unpublished`() {
        assertThat(mapper.map(page().copy(inTrash = true), reference()).publicationStatus).isEqualTo(ImportedPublicationStatus.UNPUBLISHED)
        assertThat(mapper.map(page(publicUrl = null), reference()).publicationStatus).isEqualTo(ImportedPublicationStatus.UNPUBLISHED)
    }

    @Test
    fun `uses an explicit title for an empty title property and rejects malformed metadata`() {
        assertThat(mapper.map(page(properties = """{"Name":{"type":"title","title":[]}}"""), reference()).title)
            .isEqualTo("Untitled")

        assertThatThrownBy { mapper.map(page(properties = "{}"), reference()) }
            .isInstanceOf(SourceMappingException::class.java)
        assertThatThrownBy { mapper.map(page(lastEditedTime = "not-a-time"), reference()) }
            .isInstanceOf(SourceMappingException::class.java)
    }

    private fun page(
        id: String = "a0b1c2d3e4f56789abcdef0123456789",
        properties: String = """{"Name":{"type":"title","title":[{"plain_text":"Title"}]}}""",
        publicUrl: String? = "https://workspace.notion.site/a0b1c2d3e4f56789abcdef0123456789",
        lastEditedTime: String = "2026-08-25T00:00:00Z",
    ) = NotionPageResponse(
        id = id,
        parent = NotionPageParentResponse("workspace", null),
        url = "https://www.notion.so/$id",
        publicUrl = publicUrl,
        inTrash = false,
        lastEditedTime = lastEditedTime,
        properties = objectMapper.readTree(properties),
    )

    private fun reference(externalId: String = "a0b1c2d3e4f56789abcdef0123456789") = SourceDocumentRef(SourceId("notion-main"), externalId)
}
