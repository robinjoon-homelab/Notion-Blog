package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseProperty
import java.time.DateTimeException

class NotionDatabaseCellMapperUnsupportedAndMalformedTest {
    private val fixture = NotionDatabaseCellFixture()
    private val mapper = fixture.mapper

    @Test
    fun `marks unknown or complex properties unsupported without leaking their payloads`() {
        val examples =
            listOf(
                "relation" to """[{"id":"private-related-page"}]""",
                "rollup" to """{"type":"array","array":[{"type":"relation","relation":[{"id":"private-related-page"}]}]}""",
                "formula" to """{"type":"unsupported","unsupported":{}}""",
                "rollup" to """{"type":"unsupported","unsupported":{}}""",
                "future_property" to """{"private":"private@example.com"}""",
            )

        examples.forEach { (type, value) ->
            assertThat(fixture.label(fixture.cell(type, value))).describedAs(type).isEqualTo("[Unsupported property value]")
        }
        val future =
            mapper.mapRow(
                fixture.page("""{"Future":{"id":"future","type":"future_property"}}"""),
                listOf(NotionDatabaseProperty("future", "Future", "future_property")),
            )
        assertThat(fixture.label(future.cells.single())).isEqualTo("[Unsupported property value]")
    }

    @Test
    fun `marks incomplete references and missing selected values explicitly`() {
        assertThat(fixture.label(fixture.cell("relation", """[{"id":"private-related-page"}]""", extra = ",\"has_more\":true")))
            .isEqualTo("[Incomplete property value]")
        assertThat(fixture.label(fixture.cell("rich_text", "[${fixture.richText("Truncated")}]", extra = ",\"has_more\":true")))
            .isEqualTo("[Incomplete property value]")
        assertThat(fixture.label(fixture.cell("rollup", """{"type":"incomplete","incomplete":{}}""")))
            .isEqualTo("[Incomplete property value]")
        val people = (1..25).joinToString { """{"id":"private-$it","name":"Person $it"}""" }
        assertThat(fixture.label(fixture.cell("people", "[$people]"))).isEqualTo("[Incomplete property value]")
        val mentions =
            (1..25).joinToString {
                """
                {"type":"mention","mention":{"type":"user","user":{"id":"private-$it"}},"plain_text":"Person $it","annotations":{}}
                """.trimIndent()
            }
        listOf("title", "rich_text").forEach { type ->
            assertThat(fixture.label(fixture.cell(type, "[$mentions]"))).isEqualTo("[Incomplete property value]")
        }
        val missing = mapper.mapRow(fixture.page("{}"), listOf(NotionDatabaseProperty("not-returned", "Value", "number")))
        assertThat(fixture.label(missing.cells.single())).isEqualTo("[Incomplete property value]")
    }

    @Test
    fun `rejects malformed known property types rather than stringifying arbitrary values`() {
        val malformed =
            listOf(
                "number" to "\"42\"",
                "checkbox" to "\"yes\"",
                "select" to "[]",
                "status" to """{"name":42}""",
                "multi_select" to """[{"name":false}]""",
                "date" to """{"start":"not-a-date"}""",
                "date" to """{"start":"2026-08-31","end":42}""",
                "date" to """{"start":"2026-08-31T10:00:00","time_zone":"not/a-time-zone"}""",
                "url" to "{}",
                "email" to "[]",
                "phone_number" to "42",
                "people" to "{}",
                "people" to """[{"name":true}]""",
                "files" to """[{"name":{}}]""",
                "title" to "{}",
                "rich_text" to "[{}]",
                "rich_text" to """[{"type":"text","text":{"content":42},"annotations":{}}]""",
                "created_time" to "\"not-a-time\"",
                "unique_id" to """{"number":1.5,"prefix":null}""",
                "formula" to """{"type":"number","number":[]}""",
                "rollup" to """{"type":"array","array":{}}""",
                "relation" to "{}",
            )

        malformed.forEach { (type, value) ->
            assertThatThrownBy { fixture.cell(type, value) }
                .describedAs("%s: %s", type, value)
                .isInstanceOf(NotionBlockMappingException::class.java)
        }
        assertThatThrownBy { fixture.cell("number", "1", extra = ",\"has_more\":\"yes\"") }
            .isInstanceOf(NotionBlockMappingException::class.java)
        assertThatThrownBy {
            mapper.mapRow(
                fixture.page("""{"Value":{"id":"selected","type":"number"}}"""),
                listOf(NotionDatabaseProperty("selected", "Value", "number")),
            )
        }.isInstanceOf(NotionBlockMappingException::class.java)
    }

    @Test
    fun `rejects mismatched or duplicate selected property ids`() {
        assertThatThrownBy {
            mapper.mapRow(
                fixture.page("""{"value":{"id":"selected","type":"rich_text","rich_text":[]}}"""),
                listOf(NotionDatabaseProperty("selected", "Value", "number")),
            )
        }.isInstanceOf(NotionBlockMappingException::class.java)
        assertThatThrownBy {
            mapper.mapRow(
                fixture.page("""{"one":{"id":"selected","type":"number","number":1},"two":{"id":"selected","type":"number","number":2}}"""),
                listOf(NotionDatabaseProperty("selected", "Value", "number")),
            )
        }.isInstanceOf(NotionBlockMappingException::class.java)
    }

    @Test
    fun `retains the cause when a date property contains an invalid time zone`() {
        assertThatThrownBy {
            fixture.cell("date", """{"start":"2026-08-31","time_zone":"invalid-zone"}""")
        }.isInstanceOf(NotionBlockMappingException::class.java)
            .hasMessage("date property contains an invalid time zone")
            .hasCauseInstanceOf(DateTimeException::class.java)
    }
}
