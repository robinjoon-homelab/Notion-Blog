package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPostsConfiguration
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostCatalog
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import java.net.URI
import java.time.Duration

class NotionPostCatalogTest {
    private val server = MockWebServer()
    private val mapper = JsonMapper.builder().build()
    private val sourceId = SourceId("notion")

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `scan follows every cursor page and drops trashed rows and duplicate IDs`() {
        enqueue(SCHEMA)
        enqueue(list(row(ROW_A, "First"), row(ROW_TRASHED, "Gone", inTrash = true), cursor = "c2"))
        enqueue(list(row(ROW_A, "First again"), row(ROW_B, "Second")))

        val entries = catalog().scan()

        assertThat(
            entries.map { it.sourceDocument },
        ).containsExactly(SourceDocumentRef(sourceId, ROW_A), SourceDocumentRef(sourceId, ROW_B))
        assertThat(entries.map { it.title }).containsExactly("First", "Second")
        server.takeRequest()
        val first = server.takeRequest()
        assertThat(first.method).isEqualTo("POST")
        assertThat(first.path).isEqualTo("/v1/data_sources/$DATA_SOURCE/query?filter_properties=title")
        assertThat(mapper.readTree(first.body.readUtf8()).get("is_archived").asBoolean()).isFalse()
        assertThat(mapper.readTree(server.takeRequest().body.readUtf8()).get("start_cursor").asString()).isEqualTo("c2")
    }

    @Test
    fun `scan fails when the cursor does not advance instead of returning a partial catalog`() {
        enqueue(SCHEMA)
        enqueue(list(row(ROW_A, "First"), cursor = "same"))
        enqueue(list(row(ROW_B, "Second"), cursor = "same"))

        assertThatThrownBy { catalog().scan() }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `scan rejects an incomplete result instead of returning a partial catalog`() {
        enqueue(SCHEMA)
        enqueue(
            """{"object":"list","results":[],"has_more":false,"next_cursor":null,
                "request_status":{"type":"incomplete","incomplete_reason":"query_result_limit_reached"}}""",
        )

        assertThatThrownBy { catalog().scan() }.isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `scan fails when the data source schema lacks the configured summary property`() {
        enqueue(SCHEMA.replace("\"Summary\":{\"id\":\"sum\",\"name\":\"Summary\",\"type\":\"rich_text\"},", ""))

        assertThatThrownBy { catalog().scan() }.isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `a blank data source configuration fails with a configuration error`() {
        val unconfigured = catalog(NotionPostsConfiguration(dataSourceId = ""))

        assertThatThrownBy { unconfigured.scan() }.isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `edit URL is a Notion page address without hyphens`() {
        val reference = SourceDocumentRef(sourceId, ROW_A.uppercase())

        assertThat(catalog().editUrl(reference)).isEqualTo(URI("https://www.notion.so/$ROW_A"))
    }

    private fun catalog(posts: NotionPostsConfiguration = NotionPostsConfiguration(DATA_SOURCE, "sum", "tag")) =
        NotionPostCatalog(
            sourceId,
            NotionApiClient(server.url("/v1").toString(), TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(5)),
            posts,
        )

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body.trimIndent()))
    }

    private fun list(
        vararg rows: String,
        cursor: String? = null,
    ) = """{"object":"list","results":[${rows.joinToString(",")}],"has_more":${cursor != null},
        "next_cursor":${cursor?.let { "\"$it\"" }},"request_status":{"type":"complete"}}"""

    private fun row(
        id: String,
        title: String,
        inTrash: Boolean = false,
    ) = """{"object":"page","id":"$id","parent":{"type":"data_source_id","data_source_id":"$DATA_SOURCE"},
        "url":"https://www.notion.so/$id","public_url":null,"in_trash":$inTrash,"last_edited_time":"2026-10-05T00:00:00Z",
        "properties":{"Name":{"id":"title","type":"title","title":[{"plain_text":"$title"}]}}}"""

    private companion object {
        const val DATA_SOURCE = "cccccccccccccccccccccccccccccccc"
        const val ROW_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ROW_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val ROW_TRASHED = "dddddddddddddddddddddddddddddddd"
        const val TOKEN = "test-token"
        const val SCHEMA =
            """{"object":"data_source","id":"$DATA_SOURCE","in_trash":false,
            "parent":{"type":"database_id","database_id":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"},
            "properties":{"Name":{"id":"title","name":"Name","type":"title"},
            "Summary":{"id":"sum","name":"Summary","type":"rich_text"},
            "Tags":{"id":"tag","name":"Tags","type":"multi_select"}}}"""
    }
}
