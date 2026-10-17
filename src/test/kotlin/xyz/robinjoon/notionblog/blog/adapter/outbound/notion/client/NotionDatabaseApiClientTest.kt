package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import java.time.Duration

class NotionDatabaseApiClientTest {
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
    fun `retrieves database metadata with the existing authenticated versioned client`() {
        enqueueJson(
            """
            {"object":"database","id":"database-1","title":[{"plain_text":"진행 "},{"plain_text":"현황"}],
             "url":"https://app.notion.com/database-1","public_url":null,"in_trash":false,
             "parent":{"type":"page_id","page_id":"parent-page"},
             "data_sources":[{"id":"source-1","name":"글"},{"id":"source-2","name":"기록"}]}
            """,
        )

        val database = client().fetchDatabase("database-1")

        assertThat(database.id).isEqualTo("database-1")
        assertThat(database.title).isEqualTo("진행 현황")
        assertThat(database.url).isEqualTo("https://app.notion.com/database-1")
        assertThat(database.inTrash).isFalse()
        assertThat(database.parent).isEqualTo(NotionParentResponse.Page("parent-page"))
        assertThat(database.dataSourceIds).containsExactly("source-1", "source-2")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/v1/databases/database-1")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token")
        assertThat(request.getHeader("Notion-Version")).isEqualTo("2026-03-11")
    }

    @Test
    fun `returns one view reference page with its continuation cursor`() {
        enqueueJson(
            """
            {"object":"list","type":"view","view":{},"results":[{"object":"view","id":"view-2"}],
             "has_more":true,"next_cursor":"cursor-3","request_status":{"type":"complete"}}
            """,
        )

        val page = client().fetchDatabaseViews("database-1", "cursor-2")

        assertThat(page.results).containsExactly("view-2")
        assertThat(page.hasMore).isTrue()
        assertThat(page.nextCursor).isEqualTo("cursor-3")
        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameter("database_id")).isEqualTo("database-1")
        assertThat(request.requestUrl?.queryParameter("page_size")).isEqualTo("100")
        assertThat(request.requestUrl?.queryParameter("start_cursor")).isEqualTo("cursor-2")
    }

    @Test
    fun `reads stable schema identifiers separately from display names`() {
        enqueueJson(
            """
            {"object":"data_source","id":"source-1","parent":{"type":"database_id","database_id":"database-1"},
             "in_trash":false,"properties":{
              "새 이름":{"id":"title","name":"새 이름","type":"title","title":{}},
              "상태":{"id":"status-id","name":"상태","type":"status","status":{"options":[]}}}}
            """,
        )

        val schema = client().fetchDataSource("source-1")

        assertThat(schema.id).isEqualTo("source-1")
        assertThat(schema.parent).isEqualTo(NotionParentResponse.Database("database-1"))
        assertThat(schema.inTrash).isFalse()
        assertThat(schema.properties).containsExactly(
            NotionDatabaseProperty("title", "새 이름", "title"),
            NotionDatabaseProperty("status-id", "상태", "status"),
        )
        assertThat(server.takeRequest().path).isEqualTo("/v1/data_sources/source-1")
    }

    @Test
    fun `creates and paginates a saved view query without reconstructing filters or sorts`() {
        enqueueJson(
            """
            {"object":"view_query","id":"query-1","view_id":"view-1","expires_at":"2026-08-31T00:15:00Z",
             "total_count":2,"results":[{"object":"page","id":"row-2"}],"has_more":true,"next_cursor":"next"}
            """,
        )
        enqueueJson(
            """
            {"object":"list","type":"page","page":{},"results":[{"object":"page","id":"row-1"}],
             "has_more":false,"next_cursor":null}
            """,
        )
        val api = client()

        val query = api.createViewQuery("view-1")
        val nextPage = api.fetchViewQueryResults("view-1", query.queryId, "next")

        assertThat(query.queryId).isEqualTo("query-1")
        assertThat(query.viewId).isEqualTo("view-1")
        assertThat(query.page.results).containsExactly("row-2")
        assertThat(nextPage.results).containsExactly("row-1")
        assertThat(nextPage.hasMore).isFalse()
        val createRequest = server.takeRequest()
        assertThat(createRequest.method).isEqualTo("POST")
        assertThat(createRequest.path).isEqualTo("/v1/views/view-1/queries")
        assertThat(createRequest.body.readUtf8()).isEqualTo("{\"page_size\":100}")
        val nextRequest = server.takeRequest()
        assertThat(nextRequest.method).isEqualTo("GET")
        assertThat(nextRequest.path).isEqualTo("/v1/views/view-1/queries/query-1?page_size=100&start_cursor=next")
    }

    @ParameterizedTest
    @ValueSource(strings = ["incomplete", "unknown"])
    fun `rejects non-complete status on view lists and every query page`(status: String) {
        val response = """
            {"object":"list","type":"view","view":{},"page":{},"id":"query-1","view_id":"view-1",
             "expires_at":"2026-08-31T00:15:00Z","total_count":0,
             "results":[],"has_more":false,"next_cursor":null,
             "request_status":{"type":"$status","incomplete_reason":"query_result_limit_reached"}}
        """
        val api = client()
        enqueueJson(response)
        assertThatThrownBy { api.fetchDatabaseViews("database-1") }.isInstanceOf(SourceConfigurationException::class.java)
        enqueueJson(response.replace("\"object\":\"list\"", "\"object\":\"view_query\""))
        assertThatThrownBy { api.createViewQuery("view-1") }.isInstanceOf(SourceConfigurationException::class.java)
        enqueueJson(response.replace("\"type\":\"view\"", "\"type\":\"page\""))
        assertThatThrownBy { api.fetchViewQueryResults("view-1", "query-1", "cursor") }
            .isInstanceOf(SourceConfigurationException::class.java)
        enqueueJson(response)
        assertThatThrownBy { api.fetchSettingsRows("source-1") }.isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `uses the existing safe access error for inaccessible databases`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("private database details"))

        assertThatThrownBy { client().fetchDatabase("database-1") }
            .isInstanceOf(SourceAccessException::class.java)
            .hasMessageNotContaining("private database details")
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
}
