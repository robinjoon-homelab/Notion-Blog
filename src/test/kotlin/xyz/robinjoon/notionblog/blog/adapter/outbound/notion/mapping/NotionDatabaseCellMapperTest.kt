package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken
import java.net.URI

class NotionDatabaseCellMapperTest {
    private val fixture = NotionDatabaseCellFixture()
    private val mapper = fixture.mapper

    @Test
    fun `keeps rich text annotations equations and safe links without storing HTML`() {
        val cell =
            fixture.cell(
                "rich_text",
                """
                [
                  {"type":"text","text":{"content":"<script>alert(1)</script>","link":{"url":"https://example.com"}},
                    "annotations":{"bold":true,"italic":true,"underline":true,"strikethrough":true,"code":true,
                    "color":"red_background"}},
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
        val examples =
            listOf(
                Triple("number", "12.50", "12.5"),
                Triple("checkbox", "true", "true"),
                Triple("checkbox", "false", "false"),
                Triple("select", """{"id":"option-id","name":"Ready","color":"green"}""", "Ready"),
                Triple("status", """{"id":"status-id","name":"Done","color":"blue"}""", "Done"),
                Triple("multi_select", """[{"name":"Kotlin"},{"name":"Spring"}]""", "Kotlin, Spring"),
                Triple("date", """{"start":"2026-08-31","end":"2026-09-01","time_zone":null}""", "2026-08-31 – 2026-09-01"),
                Triple("date", """{"start":"2026-08-31T10:00:00+09:00","end":null}""", "2026-08-31T10:00:00+09:00"),
                Triple(
                    "date",
                    """{"start":"2026-08-31T10:00:00","end":null,"time_zone":"Asia/Seoul"}""",
                    "2026-08-31T10:00:00 (Asia/Seoul)",
                ),
                Triple("email", "\"public@example.com\"", "public@example.com"),
                Triple("phone_number", "\"+82 10 1234 5678\"", "+82 10 1234 5678"),
                Triple("created_time", "\"2026-08-30T12:00:00.000Z\"", "2026-08-30T12:00:00.000Z"),
                Triple("last_edited_time", "\"2026-08-31T12:00:00.000Z\"", "2026-08-31T12:00:00.000Z"),
                Triple("unique_id", """{"number":3,"prefix":"TASK"}""", "TASK-3"),
                Triple("unique_id", """{"number":3,"prefix":null}""", "3"),
            )

        examples.forEach { (type, value, expected) ->
            assertThat(fixture.label(fixture.cell(type, value))).describedAs(type).isEqualTo(expected)
        }
    }

    @Test
    fun `maps formula and rollup scalar results without traversing references`() {
        val examples =
            listOf(
                Triple("formula", """{"type":"number","number":56}""", "56"),
                Triple("formula", """{"type":"string","string":"<b>result</b>"}""", "<b>result</b>"),
                Triple("formula", """{"type":"boolean","boolean":true}""", "true"),
                Triple("formula", """{"type":"date","date":{"start":"2026-08-31","end":null}}""", "2026-08-31"),
                Triple("rollup", """{"type":"number","number":2,"function":"count"}""", "2"),
                Triple("rollup", """{"type":"date","date":{"start":"2026-08-31"},"function":"latest_date"}""", "2026-08-31"),
            )

        examples.forEach { (type, value, expected) ->
            assertThat(fixture.label(fixture.cell(type, value))).describedAs(type).isEqualTo(expected)
        }
    }

    @Test
    fun `uses people and file names only without exposing ids metadata emails or signed URLs`() {
        val people =
            fixture.cell(
                "people",
                """
                [{"object":"user","id":"private-user-id","name":"Ada","person":{"email":"private@example.com"},
                  "avatar_url":"https://example.com/private-avatar"},{"object":"user","id":"unknown-private-user","name":null}]
                """.trimIndent(),
            )
        val files =
            fixture.cell(
                "files",
                """
                [{"name":"Plan.pdf","type":"file","file":{"url":"https://example.com/file?signature=private",
                  "expiry_time":"2026-09-01T00:00:00Z"}},{"name":"Notes.txt","type":"external",
                  "external":{"url":"https://example.com/notes"}}]
                """.trimIndent(),
            )

        assertThat(fixture.label(people)).isEqualTo("Ada, [Name unavailable]")
        assertThat(fixture.label(files)).isEqualTo("Plan.pdf, Notes.txt")
        assertThat((people + files).toString())
            .doesNotContain("private", "example.com", "unknown-private-user", "expiry_time")
        assertThat((people + files).map { (it as InlineContent.Text).link }).containsOnlyNulls()
        assertThat(
            fixture.label(
                fixture.cell("created_by", """{"id":"private-creator","name":"Author","person":{"email":"private@example.com"}}"""),
            ),
        ).isEqualTo("Author")
        assertThat(fixture.label(fixture.cell("last_edited_by", """{"id":"private-editor"}""")))
            .isEqualTo("[Name unavailable]")
    }

    @Test
    fun `links only valid http URLs and leaves unsafe URL property values as plain text`() {
        val valid = fixture.cell("url", "\"https://example.com/docs\"").single() as InlineContent.Text
        assertThat(valid.link).isEqualTo(LinkTarget.ExternalUrl(URI("https://example.com/docs")))

        listOf(
            "javascript:alert(1)",
            "data:text/html,boom",
            "https:relative",
            "https://",
            "https://user:secret@example.com",
        ).forEach { value ->
            val unsafe = fixture.cell("url", fixture.json.writeValueAsString(value)).single() as InlineContent.Text
            assertThat(unsafe.text).isEqualTo(value)
            assertThat(unsafe.link).describedAs(value).isNull()
            val title = fixture.cell("title", "[${fixture.richText("Title")}]", publicUrl = value).single() as InlineContent.Text
            assertThat(title.link).describedAs(value).isEqualTo(fixture.pageLink(null))
        }
        assertThatThrownBy {
            fixture.cell(
                "rich_text",
                """[{"type":"text","text":{"content":"Unsafe","link":{"url":"javascript:alert(1)"}},"annotations":{}}]""",
            )
        }.isInstanceOf(NotionBlockMappingException::class.java)
        assertThatThrownBy {
            fixture.cell(
                "rich_text",
                """[{"type":"text","text":{"content":"Malformed","link":{"url":"https://example.com/a b"}},"annotations":{}}]""",
            )
        }.isInstanceOf(NotionBlockMappingException::class.java)
    }

    @Test
    fun `leaves null property values and empty arrays empty instead of inventing values`() {
        listOf(
            "title",
            "rich_text",
            "number",
            "checkbox",
            "select",
            "multi_select",
            "status",
            "date",
            "url",
            "email",
            "phone_number",
            "people",
            "files",
            "formula",
            "rollup",
            "unique_id",
        ).forEach { type ->
            assertThat(fixture.cell(type, "null")).describedAs(type).isEmpty()
        }
        listOf("title", "rich_text", "multi_select", "people", "files", "relation").forEach { type ->
            assertThat(fixture.cell(type, "[]")).describedAs(type).isEmpty()
        }
        assertThat(fixture.cell("formula", """{"type":"string","string":null}""")).isEmpty()
    }

    @Test
    fun `does not call long plain rich text incomplete when no references were truncated`() {
        val text = (1..30).joinToString { fixture.richText("Text $it") }

        assertThat(fixture.cell("rich_text", "[$text]")).hasSize(30)
    }

    @Test
    fun `preserves empty and whitespace rich text fragments between words`() {
        val values = listOf("A", " ", "B", "", "\n")
        val richText = values.joinToString(transform = fixture::richText)

        listOf("title", "rich_text").forEach { type ->
            assertThat(fixture.cell(type, "[$richText]").map { (it as InlineContent.Text).text })
                .describedAs(type)
                .containsExactlyElementsOf(values)
        }
    }
}
