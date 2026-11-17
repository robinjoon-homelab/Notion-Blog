package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.ROOT
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.block
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.page
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionPostSourceFixture.source
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionMigrationDates
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import java.util.UUID

class NotionMappingFailureDiagnosticTest {
    @Test
    fun `block mapping reports the exact child UUID without attributing the error to its parent`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(block(PARENT, "paragraph", true, """{"rich_text":[]}"""))
        every { client.fetchDirectBlockChildren(PARENT) } returns
            listOf(block(CHILD, "numbered_list_item", payload = """{"rich_text":[],"list_start_index":0}"""))

        val failure = assertThrows<SourceMappingException> { source(client).fetch(reference(ROOT)) }
        val diagnostic = requireNotNull(failure.diagnostic)

        assertThat(diagnostic.stage).isEqualTo(MigrationFailureStage.BODY)
        assertThat(diagnostic.blockId).isEqualTo(UUID.fromString(CHILD))
        assertThat(diagnostic.exceptionType).isEqualTo("NotionBlockMappingException")
        assertThat(diagnostic.reason).contains("list_start_index", "양의 정수")
    }

    @Test
    fun `foreign payload type and invalid block id cannot enter diagnostic text`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns listOf(block("secret-token", "secret-payload", payload = "[]"))

        val failure = assertThrows<SourceMappingException> { source(client).fetch(reference(ROOT)) }
        val diagnostic = requireNotNull(failure.diagnostic)

        assertThat(diagnostic.blockId).isNull()
        assertThat(diagnostic.toString()).doesNotContain("secret-token", "secret-payload")
    }

    @Test
    fun `an invalid Notion reference identifies its containing block without exposing the invalid value`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchPage(ROOT) } returns page(ROOT)
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(block(CHILD, "link_to_page", payload = """{"type":"page_id","page_id":"private-token"}"""))

        val failure = assertThrows<SourceMappingException> { source(client).fetch(reference(ROOT)) }
        val diagnostic = requireNotNull(failure.diagnostic)

        assertThat(diagnostic.blockId).isEqualTo(UUID.fromString(CHILD))
        assertThat(diagnostic.exceptionType).isEqualTo("SourceMappingException")
        assertThat(diagnostic.toString()).doesNotContain("private-token")
    }

    @Test
    fun `empty and invalid authored dates have distinct reasons and never expose their values`() {
        val mapper = JsonMapper.builder().build()
        val empty = mapper.readTree("""{"type":"date","date":null}""")
        val invalid = mapper.readTree("""{"type":"date","date":{"start":"secret-token"}}""")

        val emptyFailure = assertThrows<SourceMappingException> { NotionMigrationDates.read(empty) }
        val invalidFailure = assertThrows<SourceMappingException> { NotionMigrationDates.read(invalid) }

        assertThat(requireNotNull(emptyFailure.diagnostic).stage).isEqualTo(MigrationFailureStage.AUTHORED_AT)
        assertThat(requireNotNull(emptyFailure.diagnostic).reason).contains("비어")
        assertThat(requireNotNull(invalidFailure.diagnostic).reason).contains("형식")
        assertThat(requireNotNull(invalidFailure.diagnostic).exceptionType).isEqualTo("DateTimeParseException")
        assertThat(invalidFailure.diagnostic.toString()).doesNotContain("secret-token")
    }

    @Test
    fun `a malformed date container or non string start is a format error instead of an empty date`() {
        val mapper = JsonMapper.builder().build()
        val malformed = listOf("""{"type":"date","date":7}""", """{"type":"date","date":{"start":7}}""")

        malformed.forEach { value ->
            val failure = assertThrows<SourceMappingException> { NotionMigrationDates.read(mapper.readTree(value)) }
            assertThat(requireNotNull(failure.diagnostic).reason).contains("형식")
            assertThat(requireNotNull(failure.diagnostic).stage).isEqualTo(MigrationFailureStage.AUTHORED_AT)
        }
    }

    private companion object {
        const val PARENT = "76ee2d0a-976a-824a-841f-0110b88357dc"
        const val CHILD = "335e2d0a-976a-837d-b9f1-819ca3e19953"
    }
}
