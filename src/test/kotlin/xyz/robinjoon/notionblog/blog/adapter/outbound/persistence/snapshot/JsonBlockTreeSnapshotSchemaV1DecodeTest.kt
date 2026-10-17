package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
import java.net.URI

class JsonBlockTreeSnapshotSchemaV1DecodeTest {
    private val codec = JsonBlockTreeSnapshotCodec()

    @Test
    fun `decodes an existing data table snapshot into the canonical table layout`() {
        val decoded =
            codec.decode(
                checkNotNull(javaClass.getResourceAsStream("/snapshot/schema-v1/legacy-data-table.json"))
                    .bufferedReader()
                    .use { it.readText() },
            )

        val expected =
            DataViewContent.Table(
                DataSet(
                    "Legacy tasks",
                    listOf(DataColumn("Name"), DataColumn("Status")),
                    listOf(
                        DataRow(
                            listOf(
                                listOf(
                                    InlineContent.Text(
                                        "Published row",
                                        TextAnnotations(bold = true),
                                        LinkTarget.ExternalUrl(URI("https://example.com/row")),
                                    ),
                                ),
                                emptyList(),
                            ),
                        ),
                    ),
                ),
            )
        assertThat(decoded.roots.single().content).isEqualTo(expected)
        assertThat(codec.encode(decoded)).contains("\"kind\":\"data_view\"", "\"layout\":\"table\"").doesNotContain("data_table")
    }

    @Test
    fun `falls back to unsupported content for an unknown logical block kind`() {
        val decoded =
            codec.decode(
                """
                {
                  "schemaVersion": 1,
                  "kind": "block_tree_snapshot",
                  "blocks": [
                    {
                      "id": "future-block",
                      "kind": "future_widget",
                      "style": {},
                      "content": "unrecognized future payload",
                      "children": [{
                        "id": "child",
                        "kind": "paragraph",
                        "style": {},
                        "content": {"richText": []},
                        "children": []
                      }]
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertThat(decoded.roots.single().content).isEqualTo(UnsupportedBlockContent("future_widget"))
        assertThat(
            decoded.roots
                .single()
                .children
                .single()
                .content,
        ).isEqualTo(TextBlockContent.Paragraph(emptyList()))
    }

    @Test
    fun `decodes older schema version one snapshots with defaults for newly preserved block fields`() {
        val decoded =
            codec.decode(
                """
                {
                  "schemaVersion": 1,
                  "kind": "block_tree_snapshot",
                  "blocks": [
                    {
                      "id":"numbered",
                      "kind":"numbered_list_item",
                      "style":{},
                      "content":{"richText":[],"startNumber":1,"displayFormat":"decimal"},
                      "children":[]
                    },
                    {
                      "id":"database",
                      "kind":"database_link",
                      "style":{},
                      "content":{"reference":{"sourceId":"notion-main","externalId":"database-id"},"originalUrl":null},
                      "children":[]
                    },
                    {
                      "id":"template",
                      "kind":"template",
                      "style":{},
                      "content":{},
                      "children":[]
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertThat(decoded.roots[0].content).isEqualTo(ListBlockContent.NumberedItem(emptyList()))
        assertThat(decoded.roots[1].content).isEqualTo(
            ReferenceBlockContent.DatabaseLink(SourceDocumentRef(SourceId("notion-main"), "database-id"), null),
        )
        assertThat(decoded.roots[2].content).isEqualTo(ReusableBlockContent.Template())
    }
}
