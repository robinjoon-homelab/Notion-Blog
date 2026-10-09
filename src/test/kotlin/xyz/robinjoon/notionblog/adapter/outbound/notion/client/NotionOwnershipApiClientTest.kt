package xyz.robinjoon.notionblog.adapter.outbound.notion.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import java.time.Duration

class NotionOwnershipApiClientTest {
    private val server = MockWebServer()

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `preserves every supported parent shape without treating unknown parents as workspace`() {
        val parents =
            listOf(
                """{"type":"page_id","page_id":"page-1"}""" to NotionParentResponse.Page("page-1"),
                """{"type":"block_id","block_id":"block-1"}""" to NotionParentResponse.Block("block-1"),
                """{"type":"database_id","database_id":"database-1"}""" to NotionParentResponse.Database("database-1"),
                """{"type":"data_source_id","data_source_id":"source-1","database_id":"database-1"}""" to
                    NotionParentResponse.DataSource("source-1"),
                """{"type":"workspace","workspace":true}""" to NotionParentResponse.Workspace,
                """{"type":"future_parent","future_id":"private-value"}""" to NotionParentResponse.Unsupported("future_parent"),
            )
        val api = client()

        parents.forEach { (parent, expected) ->
            enqueueDatabase(""""parent":$parent,"data_sources":[]""")

            assertThat(api.fetchDatabase("database-1").parent).isEqualTo(expected)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"type\":\"page_id\"}",
            "{\"type\":\"page_id\",\"page_id\":null}",
            "{\"type\":\"block_id\",\"block_id\":42}",
            "{\"type\":\"database_id\",\"database_id\":\" \"}",
            "{\"type\":\"data_source_id\",\"data_source_id\":\"\"}",
            "{\"type\":\"workspace\",\"workspace\":false}",
            "{\"type\":\"workspace\"}",
            "{\"page_id\":\"page-1\"}",
        ],
    )
    fun `rejects incomplete known parents instead of inventing ownership`(parent: String) {
        enqueueDatabase(""""parent":$parent,"data_sources":[]""")

        assertThatThrownBy { client().fetchDatabase("database-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\"data_sources\":[]",
            "\"parent\":null,\"data_sources\":[]",
            "\"parent\":{\"type\":\"workspace\",\"workspace\":true}",
            "\"parent\":{\"type\":\"workspace\",\"workspace\":true},\"data_sources\":null",
            "\"parent\":{\"type\":\"workspace\",\"workspace\":true},\"data_sources\":[{}]",
        ],
    )
    fun `does not replace missing database ownership metadata with an empty source list`(ownership: String) {
        enqueueDatabase(ownership)

        assertThatThrownBy { client().fetchDatabase("database-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `retains linked data source parents and trash state for the membership reader`() {
        enqueueJson(
            """
            {"object":"data_source","id":"source-1","properties":{},"in_trash":true,
             "parent":{"type":"data_source_id","data_source_id":"origin-source","database_id":"origin-database"}}
            """,
        )

        val source = client().fetchDataSource("source-1")

        assertThat(source.parent).isEqualTo(NotionParentResponse.DataSource("origin-source"))
        assertThat(source.inTrash).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ",\"parent\":{\"type\":\"database_id\",\"database_id\":\"database-1\"}", ",\"in_trash\":false"])
    fun `requires both data source parent and trash state`(metadata: String) {
        enqueueJson("""{"object":"data_source","id":"source-1","properties":{}$metadata}""")

        assertThatThrownBy { client().fetchDataSource("source-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `queries one unfiltered data source page and preserves unpublished and trashed row metadata`() {
        enqueueJson(
            """
            {"object":"list","results":[${page("row-1", "null", false)},${page("row-2", "\"https://example.com/row-2\"", true)}],
             "has_more":true,"next_cursor":"next-rows","request_status":{"type":"complete"}}
            """,
        )

        val result = client().queryDataSourcePage("source-1", "previous-rows")

        assertThat(result.results.map { it.id }).containsExactly("row-1", "row-2")
        assertThat(result.results.map { it.parent.dataSourceId }).containsExactly("source-1", "source-1")
        assertThat(result.results.first().publicUrl).isNull()
        assertThat(result.results.last().inTrash).isTrue()
        assertThat(result.hasMore).isTrue()
        assertThat(result.nextCursor).isEqualTo("next-rows")
        assertThat(server.requestCount).isEqualTo(1)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/data_sources/source-1/query")
        assertThat(request.getHeader("Notion-Version")).isEqualTo("2026-03-11")
        val body = JsonMapper.builder().build().readTree(request.body.readUtf8())
        assertThat(
            body,
        ).isEqualTo(JsonMapper.builder().build().readTree("""{"result_type":"page","page_size":100,"start_cursor":"previous-rows"}"""))
    }

    @Test
    fun `omits the cursor from the first source query request`() {
        enqueueJson("""{"object":"list","results":[],"has_more":false,"next_cursor":null}""")

        assertThat(client().queryDataSourcePage("source-1").results).isEmpty()

        val body = JsonMapper.builder().build().readTree(server.takeRequest().body.readUtf8())
        assertThat(body).isEqualTo(JsonMapper.builder().build().readTree("""{"result_type":"page","page_size":100}"""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["incomplete", "unknown"])
    fun `rejects partial source query results even when there is no next cursor`(status: String) {
        enqueueJson(
            """
            {"object":"list","results":[],"has_more":false,"next_cursor":null,
             "request_status":{"type":"$status","incomplete_reason":"query_result_limit_reached"}}
            """,
        )

        assertThatThrownBy { client().queryDataSourcePage("source-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `rejects a non-list source query response`() {
        enqueueJson("""{"object":"page","results":[],"has_more":false,"next_cursor":null}""")

        assertThatThrownBy { client().queryDataSourcePage("source-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `rejects non-page source query results rather than importing a wiki data source as a page`() {
        enqueueJson(
            """
            {"object":"list","results":[${page("row-1", "null", false).replace("\"object\":\"page\"", "\"object\":\"data_source\"")}],
             "has_more":false,"next_cursor":null}
            """,
        )

        assertThatThrownBy { client().queryDataSourcePage("source-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `retrieves a block parent and accepts equivalent dashed response identifiers`() {
        enqueueJson(
            """
            {"object":"block","id":"12345678-1234-1234-1234-123456789ABC",
             "parent":{"type":"page_id","page_id":"parent-page"}}
            """,
        )

        val parent = client().fetchBlockParent("12345678123412341234123456789abc")

        assertThat(parent).isEqualTo(NotionParentResponse.Page("parent-page"))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/v1/blocks/12345678123412341234123456789abc")
    }

    @Test
    fun `rejects block parent information returned for another block`() {
        enqueueJson(
            """
            {"object":"block","id":"87654321-1234-1234-1234-123456789abc",
             "parent":{"type":"page_id","page_id":"private-parent"}}
            """,
        )

        assertThatThrownBy { client().fetchBlockParent("12345678123412341234123456789abc") }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasMessageNotContaining("private-parent")
    }

    @Test
    fun `rejects a page response as block ancestry even when its identifier matches`() {
        enqueueJson(
            """
            {"object":"page","id":"12345678-1234-1234-1234-123456789abc",
             "parent":{"type":"page_id","page_id":"parent-page"}}
            """,
        )

        assertThatThrownBy { client().fetchBlockParent("12345678123412341234123456789abc") }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `preserves access failures while retrieving block ancestry`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("private block details"))

        assertThatThrownBy { client().fetchBlockParent("12345678123412341234123456789abc") }
            .isInstanceOf(SourceAccessException::class.java)
            .hasMessageNotContaining("private block details")
    }

    private fun client(): NotionApiClient =
        NotionApiClient(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            token = "test-token",
            requestTimeout = Duration.ofSeconds(1),
            collectionTimeout = Duration.ofSeconds(2),
        )

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.trimIndent()))
    }

    private fun enqueueDatabase(ownership: String) {
        enqueueJson(
            """
            {"object":"database","id":"database-1","title":[],"url":"https://example.com/database-1",
             "public_url":null,"in_trash":false,$ownership}
            """,
        )
    }

    private fun page(
        id: String,
        publicUrl: String,
        inTrash: Boolean,
    ): String =
        """
        {"object":"page","id":"$id","parent":{"type":"data_source_id","data_source_id":"source-1"},
         "url":"https://example.com/$id","public_url":$publicUrl,"in_trash":$inTrash,
         "last_edited_time":"2026-10-05T00:00:00Z","properties":{}}
        """.trimIndent()
}
