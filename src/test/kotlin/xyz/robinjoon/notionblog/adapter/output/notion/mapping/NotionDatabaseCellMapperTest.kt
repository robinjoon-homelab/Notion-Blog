package xyz.robinjoon.notionblog.adapter.output.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI

class NotionDatabaseCellMapperTest {
    private val json = JsonMapper.builder().build()
    private val mapper = NotionDatabaseCellMapper(NotionBlockMapper(SourceId("notion-main")), SourceId("notion-main"))

    @Test
    fun `maps only visible columns by stable property ids in view order after renaming`() {
        val page = page(
            """
            {
              "New amount name":{"id":"amount-id","type":"number","number":42},
              "New title name":{"id":"title","type":"title","title":[${richText("Public title")}]},
              "Secret property":{"id":"hidden","type":"future_secret","future_secret":{"secret":"not-public"}}
            }
            """.trimIndent(),
        )

        val row = mapper.mapRow(
            page,
            listOf(
                NotionDatabaseProperty("title", "Old title name", "title"),
                NotionDatabaseProperty("amount-id", "Old amount name", "number"),
            ),
        )

        assertThat(row.cells).hasSize(2)
        assertThat(row.cells.map(::label)).containsExactly("Public title", "42")
        assertThat((row.cells.first().single() as InlineContent.Text).link)
            .isEqualTo(pageLink())
        assertThat(row.link).isEqualTo(pageLink())
        assertThat(row.icon).isNull()
        assertThat(row.cover).isNull()
        assertThat(row.toString()).doesNotContain("hidden", "Secret property", "not-public")
    }

    @Test
    fun `keeps the page reference without a visible title and drops unsafe fallback URLs`() {
        val source = page("""{"Amount":{"id":"amount","type":"number","number":42}}""")
        val columns = listOf(NotionDatabaseProperty("amount", "Amount", "number"))

        assertThat(mapper.mapRow(source, columns).link).isEqualTo(pageLink())
        listOf("javascript:alert(1)", "https://name:password@example.com", "data:text/html,x", "//example.com").forEach { unsafe ->
            assertThat(mapper.mapRow(source.copy(publicUrl = unsafe), columns).link).isEqualTo(pageLink(null))
        }
    }

    @Test
    fun `normalizes row page ids and uses the page reference for every title segment`() {
        val source = page(
            """{"Name":{"id":"title","type":"title","title":[${richText("First ")},${richText("second")}]}}""",
        ).copy(id = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")

        val row = mapper.mapRow(source, listOf(NotionDatabaseProperty("title", "Name", "title")))

        assertThat(row.link).isEqualTo(pageLink())
        assertThat(row.cells.single().map { (it as InlineContent.Text).link }).containsExactly(pageLink(), pageLink())
    }

    @Test
    fun `retains row and title page references when no safe public fallback exists`() {
        val source = page("""{"Name":{"id":"title","type":"title","title":[${richText("Read in blog")}]}}""")
        val columns = listOf(NotionDatabaseProperty("title", "Name", "title"))

        listOf(null, "javascript:alert(1)", "https://user:secret@example.com").forEach { url ->
            val row = mapper.mapRow(source.copy(publicUrl = url), columns)

            assertThat(row.link).isEqualTo(pageLink(null))
            assertThat((row.cells.single().single() as InlineContent.Text).link).isEqualTo(pageLink(null))
        }
    }

    @Test
    fun `keeps rich text annotations equations and safe links without storing HTML`() {
        val cell = cell(
            "rich_text",
            """
            [
              {"type":"text","text":{"content":"<script>alert(1)</script>","link":{"url":"https://example.com"}},"annotations":{"bold":true,"italic":true,"underline":true,"strikethrough":true,"code":true,"color":"red_background"}},
              {"type":"equation","equation":{"expression":"x^2"},"annotations":{"color":"blue"}}
            ]
            """.trimIndent(),
        )

        val text = cell.first() as InlineContent.Text
        assertThat(text.text).isEqualTo("<script>alert(1)</script>")
        assertThat(text.link).isEqualTo(LinkTarget.ExternalUrl(URI("https://example.com")))
        assertThat(text.annotations.bold && text.annotations.italic && text.annotations.underline).isTrue()
        assertThat(text.annotations.strikethrough && text.annotations.code).isTrue()
        assertThat(text.annotations.background).isEqualTo(ColorToken.RED)
        assertThat((cell.last() as InlineContent.Equation).expression).isEqualTo("x^2")
    }

    @Test
    fun `maps scalar properties to display text while preserving explicit email and phone columns`() {
        val examples = listOf(
            Triple("number", "12.50", "12.5"),
            Triple("checkbox", "true", "true"),
            Triple("checkbox", "false", "false"),
            Triple("select", """{"id":"option-id","name":"Ready","color":"green"}""", "Ready"),
            Triple("status", """{"id":"status-id","name":"Done","color":"blue"}""", "Done"),
            Triple("multi_select", """[{"name":"Kotlin"},{"name":"Spring"}]""", "Kotlin, Spring"),
            Triple("date", """{"start":"2026-08-31","end":"2026-09-01","time_zone":null}""", "2026-08-31 – 2026-09-01"),
            Triple("date", """{"start":"2026-08-31T10:00:00+09:00","end":null}""", "2026-08-31T10:00:00+09:00"),
            Triple("date", """{"start":"2026-08-31T10:00:00","end":null,"time_zone":"Asia/Seoul"}""", "2026-08-31T10:00:00 (Asia/Seoul)"),
            Triple("email", "\"public@example.com\"", "public@example.com"),
            Triple("phone_number", "\"+82 10 1234 5678\"", "+82 10 1234 5678"),
            Triple("created_time", "\"2026-08-30T12:00:00.000Z\"", "2026-08-30T12:00:00.000Z"),
            Triple("last_edited_time", "\"2026-08-31T12:00:00.000Z\"", "2026-08-31T12:00:00.000Z"),
            Triple("unique_id", """{"number":3,"prefix":"TASK"}""", "TASK-3"),
            Triple("unique_id", """{"number":3,"prefix":null}""", "3"),
        )

        examples.forEach { (type, value, expected) ->
            assertThat(label(cell(type, value))).describedAs(type).isEqualTo(expected)
        }
    }

    @Test
    fun `maps formula and rollup scalar results without traversing references`() {
        val examples = listOf(
            Triple("formula", """{"type":"number","number":56}""", "56"),
            Triple("formula", """{"type":"string","string":"<b>result</b>"}""", "<b>result</b>"),
            Triple("formula", """{"type":"boolean","boolean":true}""", "true"),
            Triple("formula", """{"type":"date","date":{"start":"2026-08-31","end":null}}""", "2026-08-31"),
            Triple("rollup", """{"type":"number","number":2,"function":"count"}""", "2"),
            Triple("rollup", """{"type":"date","date":{"start":"2026-08-31"},"function":"latest_date"}""", "2026-08-31"),
        )

        examples.forEach { (type, value, expected) ->
            assertThat(label(cell(type, value))).describedAs(type).isEqualTo(expected)
        }
    }

    @Test
    fun `uses people and file names only without exposing ids metadata emails or signed URLs`() {
        val people = cell(
            "people",
            """[{"object":"user","id":"private-user-id","name":"Ada","person":{"email":"private@example.com"},"avatar_url":"https://example.com/private-avatar"},{"object":"user","id":"unknown-private-user","name":null}]""",
        )
        val files = cell(
            "files",
            """[{"name":"Plan.pdf","type":"file","file":{"url":"https://example.com/file?signature=private","expiry_time":"2026-09-01T00:00:00Z"}},{"name":"Notes.txt","type":"external","external":{"url":"https://example.com/notes"}}]""",
        )

        assertThat(label(people)).isEqualTo("Ada, [Name unavailable]")
        assertThat(label(files)).isEqualTo("Plan.pdf, Notes.txt")
        assertThat((people + files).toString())
            .doesNotContain("private", "example.com", "unknown-private-user", "expiry_time")
        assertThat((people + files).map { (it as InlineContent.Text).link }).containsOnlyNulls()
        assertThat(label(cell("created_by", """{"id":"private-creator","name":"Author","person":{"email":"private@example.com"}}""")))
            .isEqualTo("Author")
        assertThat(label(cell("last_edited_by", """{"id":"private-editor"}""")))
            .isEqualTo("[Name unavailable]")
    }

    @Test
    fun `links only valid http URLs and leaves unsafe URL property values as plain text`() {
        val valid = cell("url", "\"https://example.com/docs\"").single() as InlineContent.Text
        assertThat(valid.link).isEqualTo(LinkTarget.ExternalUrl(URI("https://example.com/docs")))

        listOf("javascript:alert(1)", "data:text/html,boom", "https:relative", "https://", "https://user:secret@example.com").forEach { value ->
            val unsafe = cell("url", json.writeValueAsString(value)).single() as InlineContent.Text
            assertThat(unsafe.text).isEqualTo(value)
            assertThat(unsafe.link).describedAs(value).isNull()
            val title = cell("title", "[${richText("Title")}]", publicUrl = value).single() as InlineContent.Text
            assertThat(title.link).describedAs(value).isEqualTo(pageLink(null))
        }
        assertThatThrownBy {
            cell("rich_text", """[{"type":"text","text":{"content":"Unsafe","link":{"url":"javascript:alert(1)"}},"annotations":{}}]""")
        }.isInstanceOf(NotionBlockMappingException::class.java)
        assertThatThrownBy {
            cell("rich_text", """[{"type":"text","text":{"content":"Malformed","link":{"url":"https://example.com/a b"}},"annotations":{}}]""")
        }.isInstanceOf(NotionBlockMappingException::class.java)
    }

    @Test
    fun `leaves null property values and empty arrays empty instead of inventing values`() {
        listOf("title", "rich_text", "number", "checkbox", "select", "multi_select", "status", "date", "url", "email", "phone_number", "people", "files", "formula", "rollup", "unique_id").forEach { type ->
            assertThat(cell(type, "null")).describedAs(type).isEmpty()
        }
        listOf("title", "rich_text", "multi_select", "people", "files", "relation").forEach { type ->
            assertThat(cell(type, "[]")).describedAs(type).isEmpty()
        }
        assertThat(cell("formula", """{"type":"string","string":null}""")).isEmpty()
    }

    @Test
    fun `marks unknown or complex properties unsupported without leaking their payloads`() {
        val examples = listOf(
            "relation" to """[{"id":"private-related-page"}]""",
            "rollup" to """{"type":"array","array":[{"type":"relation","relation":[{"id":"private-related-page"}]}]}""",
            "formula" to """{"type":"unsupported","unsupported":{}}""",
            "rollup" to """{"type":"unsupported","unsupported":{}}""",
            "future_property" to """{"private":"private@example.com"}""",
        )

        examples.forEach { (type, value) ->
            assertThat(label(cell(type, value))).describedAs(type).isEqualTo("[Unsupported property value]")
        }
        val future = mapper.mapRow(
            page("""{"Future":{"id":"future","type":"future_property"}}"""),
            listOf(NotionDatabaseProperty("future", "Future", "future_property")),
        )
        assertThat(label(future.cells.single())).isEqualTo("[Unsupported property value]")
    }

    @Test
    fun `marks incomplete references and missing selected values explicitly`() {
        assertThat(label(cell("relation", """[{"id":"private-related-page"}]""", extra = ",\"has_more\":true")))
            .isEqualTo("[Incomplete property value]")
        assertThat(label(cell("rich_text", "[${richText("Truncated")}]", extra = ",\"has_more\":true")))
            .isEqualTo("[Incomplete property value]")
        assertThat(label(cell("rollup", """{"type":"incomplete","incomplete":{}}""")))
            .isEqualTo("[Incomplete property value]")
        val people = (1..25).joinToString { """{"id":"private-$it","name":"Person $it"}""" }
        assertThat(label(cell("people", "[$people]"))).isEqualTo("[Incomplete property value]")
        val mentions = (1..25).joinToString { """{"type":"mention","mention":{"type":"user","user":{"id":"private-$it"}},"plain_text":"Person $it","annotations":{}}""" }
        listOf("title", "rich_text").forEach { type ->
            assertThat(label(cell(type, "[$mentions]"))).isEqualTo("[Incomplete property value]")
        }
        val missing = mapper.mapRow(page("{}"), listOf(NotionDatabaseProperty("not-returned", "Value", "number")))
        assertThat(label(missing.cells.single())).isEqualTo("[Incomplete property value]")
    }

    @Test
    fun `does not call long plain rich text incomplete when no references were truncated`() {
        val text = (1..30).joinToString { richText("Text $it") }

        assertThat(cell("rich_text", "[$text]")).hasSize(30)
    }

    @Test
    fun `preserves empty and whitespace rich text fragments between words`() {
        val values = listOf("A", " ", "B", "", "\n")
        val richText = values.joinToString(transform = ::richText)

        listOf("title", "rich_text").forEach { type ->
            assertThat(cell(type, "[$richText]").map { (it as InlineContent.Text).text })
                .describedAs(type)
                .containsExactlyElementsOf(values)
        }
    }

    @Test
    fun `rejects malformed known property types rather than stringifying arbitrary values`() {
        val malformed = listOf(
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
            assertThatThrownBy { cell(type, value) }
                .describedAs("%s: %s", type, value)
                .isInstanceOf(NotionBlockMappingException::class.java)
        }
        assertThatThrownBy { cell("number", "1", extra = ",\"has_more\":\"yes\"") }
            .isInstanceOf(NotionBlockMappingException::class.java)
        assertThatThrownBy {
            mapper.mapRow(page("""{"Value":{"id":"selected","type":"number"}}"""), listOf(NotionDatabaseProperty("selected", "Value", "number")))
        }.isInstanceOf(NotionBlockMappingException::class.java)
    }

    @Test
    fun `rejects mismatched or duplicate selected property ids`() {
        assertThatThrownBy {
            mapper.mapRow(page("""{"value":{"id":"selected","type":"rich_text","rich_text":[]}}"""), listOf(NotionDatabaseProperty("selected", "Value", "number")))
        }.isInstanceOf(NotionBlockMappingException::class.java)
        assertThatThrownBy {
            mapper.mapRow(page("""{"one":{"id":"selected","type":"number","number":1},"two":{"id":"selected","type":"number","number":2}}"""), listOf(NotionDatabaseProperty("selected", "Value", "number")))
        }.isInstanceOf(NotionBlockMappingException::class.java)
    }

    private fun cell(
        type: String,
        value: String,
        extra: String = "",
        publicUrl: String? = "https://example.com/public-page",
    ): List<InlineContent> = mapper.mapRow(
        page("""{"Value":{"id":"selected","type":"$type","$type":$value$extra}}""", publicUrl),
        listOf(NotionDatabaseProperty("selected", "Value", type)),
    ).cells.single()

    private fun page(properties: String, publicUrl: String? = "https://example.com/public-page") = NotionPageResponse(
        id = "a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d",
        parent = NotionPageParentResponse("data_source_id", null),
        url = "https://www.notion.so/private-page",
        publicUrl = publicUrl,
        inTrash = false,
        lastEditedTime = "2026-08-31T12:00:00Z",
        properties = json.readTree(properties),
    )

    private fun pageLink(publicUrl: String? = "https://example.com/public-page"): LinkTarget.SourceDocument = LinkTarget.SourceDocument(
        SourceDocumentRef(SourceId("notion-main"), "a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d"),
        publicUrl?.let(::URI),
    )

    private fun richText(value: String): String = """{"type":"text","text":{"content":${json.writeValueAsString(value)},"link":null},"annotations":{}}"""

    private fun label(cell: List<InlineContent>): String = cell.joinToString("") {
        when (it) {
            is InlineContent.Text -> it.text
            is InlineContent.Equation -> it.expression
            is InlineContent.Mention -> it.label
        }
    }
}
