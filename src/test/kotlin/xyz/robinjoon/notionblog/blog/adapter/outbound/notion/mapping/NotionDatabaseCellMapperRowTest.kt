package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent

class NotionDatabaseCellMapperRowTest {
    private val fixture = NotionDatabaseCellFixture()
    private val mapper = fixture.mapper

    @Test
    fun `maps only visible columns by stable property ids in view order after renaming`() {
        val page =
            fixture.page(
                """
                {
                  "New amount name":{"id":"amount-id","type":"number","number":42},
                  "New title name":{"id":"title","type":"title","title":[${fixture.richText("Public title")}]},
                  "Secret property":{"id":"hidden","type":"future_secret","future_secret":{"secret":"not-public"}}
                }
                """.trimIndent(),
            )

        val row =
            mapper.mapRow(
                page,
                listOf(
                    NotionDatabaseProperty("title", "Old title name", "title"),
                    NotionDatabaseProperty("amount-id", "Old amount name", "number"),
                ),
            )

        assertThat(row.cells).hasSize(2)
        assertThat(row.cells.map(fixture::label)).containsExactly("Public title", "42")
        assertThat((row.cells.first().single() as InlineContent.Text).link)
            .isEqualTo(fixture.pageLink())
        assertThat(row.link).isEqualTo(fixture.pageLink())
        assertThat(row.icon).isNull()
        assertThat(row.cover).isNull()
        assertThat(row.toString()).doesNotContain("hidden", "Secret property", "not-public")
    }

    @Test
    fun `keeps the page reference without a visible title and drops unsafe fallback URLs`() {
        val source = fixture.page("""{"Amount":{"id":"amount","type":"number","number":42}}""")
        val columns = listOf(NotionDatabaseProperty("amount", "Amount", "number"))

        assertThat(mapper.mapRow(source, columns).link).isEqualTo(fixture.pageLink())
        listOf("javascript:alert(1)", "https://name:password@example.com", "data:text/html,x", "//example.com").forEach { unsafe ->
            assertThat(mapper.mapRow(source.copy(publicUrl = unsafe), columns).link).isEqualTo(fixture.pageLink(null))
        }
    }

    @Test
    fun `normalizes row page ids and uses the page reference for every title segment`() {
        val source =
            fixture
                .page(
                    """{"Name":{"id":"title","type":"title","title":[${fixture.richText("First ")},${fixture.richText("second")}]}}""",
                ).copy(id = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")

        val row = mapper.mapRow(source, listOf(NotionDatabaseProperty("title", "Name", "title")))

        assertThat(row.link).isEqualTo(fixture.pageLink())
        assertThat(row.cells.single().map { (it as InlineContent.Text).link }).containsExactly(fixture.pageLink(), fixture.pageLink())
    }

    @Test
    fun `retains row and title page references when no safe public fallback exists`() {
        val source = fixture.page("""{"Name":{"id":"title","type":"title","title":[${fixture.richText("Read in blog")}]}}""")
        val columns = listOf(NotionDatabaseProperty("title", "Name", "title"))

        listOf(null, "javascript:alert(1)", "https://user:secret@example.com").forEach { url ->
            val row = mapper.mapRow(source.copy(publicUrl = url), columns)

            assertThat(row.link).isEqualTo(fixture.pageLink(null))
            assertThat((row.cells.single().single() as InlineContent.Text).link).isEqualTo(fixture.pageLink(null))
        }
    }
}
