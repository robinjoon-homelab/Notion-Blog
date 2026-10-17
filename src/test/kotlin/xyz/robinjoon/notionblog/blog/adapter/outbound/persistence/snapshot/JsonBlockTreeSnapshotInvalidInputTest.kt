package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

class JsonBlockTreeSnapshotInvalidInputTest {
    private val codec = JsonBlockTreeSnapshotCodec()

    @Test
    fun `propagates unexpected mapper failures without treating them as invalid snapshots`() {
        val mapper = mockk<JsonMapper>()
        val failure = IllegalStateException("mapper unavailable")
        every { mapper.readTree(any<String>()) } throws failure

        assertThatThrownBy { JsonBlockTreeSnapshotCodec(mapper).decode("{}") }
            .isSameAs(failure)
    }

    @Test
    fun `retains the Jackson cause when malformed JSON is rejected`() {
        assertThatThrownBy { codec.decode("{invalid") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasCauseInstanceOf(JacksonException::class.java)
    }

    @Test
    fun `rejects unsupported data view layouts and malformed layout options`() {
        listOf(
            "\"layout\":\"board\"" to "unsupported data view layout",
            "\"layout\":\"table\",\"options\":{\"wrapCells\":true,\"frozenColumns\":2,\"showVerticalLines\":true}" to "frozen column count",
            "\"layout\":\"table\",\"options\":{\"wrapCells\":true,\"frozenColumns\":0.5,\"showVerticalLines\":true}" to "integer",
            "\"layout\":\"gallery\",\"options\":{\"size\":\"huge\",\"aspect\":\"cover\",\"layout\":\"list\"}" to "data card size",
        ).forEach { (layout, message) ->
            assertThatIllegalArgumentException()
                .isThrownBy {
                    codec.decode(
                        """
                        {"schemaVersion":1,"kind":"block_tree_snapshot","blocks":[{
                          "id":"bad-view","kind":"data_view","style":{},
                          "content":{$layout,"data":{"title":"Tasks","columns":[{"name":"Name"}],"rows":[]}},
                          "children":[]
                        }]}
                        """.trimIndent(),
                    )
                }.withMessageContaining(message)
        }
    }

    @Test
    fun `rejects malformed shared dataset fields instead of truncating or guessing`() {
        listOf(
            "\"columns\":[{\"name\":\"Name\",\"widthPixels\":12.5}],\"rows\":[]" to "integer",
            "\"columns\":[{\"name\":\"Name\",\"wrap\":\"yes\"}],\"rows\":[]" to "boolean",
            "\"columns\":[{\"name\":\"Name\"}],\"rows\":[],\"titleColumnIndex\":1" to "title column index",
            "\"columns\":[{\"name\":\"Name\"}],\"rows\":[{\"cells\":[]}]" to "column count",
        ).forEach { (fields, message) ->
            assertThatIllegalArgumentException()
                .isThrownBy {
                    codec.decode(
                        """
                        {"schemaVersion":1,"kind":"block_tree_snapshot","blocks":[{
                          "id":"bad-data","kind":"data_view","style":{},
                          "content":{"layout":"list","data":{"title":"Tasks",$fields}},"children":[]
                        }]}
                        """.trimIndent(),
                    )
                }.withMessageContaining(message)
        }
    }

    @Test
    fun `rejects data table snapshots with mismatched row widths`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                codec.decode(
                    """
                    {
                      "schemaVersion": 1,
                      "kind": "block_tree_snapshot",
                      "blocks": [{
                        "id": "broken-data-table",
                        "kind": "data_table",
                        "style": {},
                        "content": {"title": "Tasks", "columns": ["Name", "Status"], "rows": [{"cells": [[]]}]},
                        "children": []
                      }]
                    }
                    """.trimIndent(),
                )
            }.withMessage("data set rows must contain exactly the column count")
    }

    @Test
    fun `rejects unsupported snapshot schema versions`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                codec.decode("""{"schemaVersion":2,"kind":"block_tree_snapshot","blocks":[]}""")
            }.withMessage("unsupported block tree snapshot schema version: 2")
    }

    @Test
    fun `rejects malformed known block kinds instead of disguising them as unsupported`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                codec.decode(
                    """
                    {
                      "schemaVersion": 1,
                      "kind": "block_tree_snapshot",
                      "blocks": [{"id":"broken","kind":"paragraph","style":{},"children":[]}]
                    }
                    """.trimIndent(),
                )
            }.withMessageContaining("paragraph")
    }
}
