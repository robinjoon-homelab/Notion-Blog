package xyz.robinjoon.notionblog.adapter.output.notion.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryAspect
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryLayout
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGallerySize
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import java.time.Duration

class NotionDatabaseApiClientTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
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
    fun `returns a single view reference page and leaves collection limits to the reader`() {
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
        assertThat(server.requestCount).isEqualTo(1)
        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameter("database_id")).isEqualTo("database-1")
        assertThat(request.requestUrl?.queryParameter("page_size")).isEqualTo("100")
        assertThat(request.requestUrl?.queryParameter("start_cursor")).isEqualTo("cursor-2")
    }

    @Test
    fun `reads only explicitly visible properties in their returned order`() {
        enqueueJson(
            """
            {"object":"view","id":"view-1","parent":{"type":"database_id","database_id":"database-1"},
             "name":"진행 중","type":"table","data_source_id":"source-1",
             "configuration":{"type":"table","properties":[
               {"property_id":"status","property_name":"상태","visible":true},
               {"property_id":"hidden","property_name":"비공개","visible":false},
               {"property_id":"unknown-visibility"},
               {"property_id":"title","visible":true}]}}
            """,
        )

        val view = client().fetchDatabaseView("view-1")

        assertThat(view.id).isEqualTo("view-1")
        assertThat(view.databaseId).isEqualTo("database-1")
        assertThat(view.dataSourceId).isEqualTo("source-1")
        assertThat(view.name).isEqualTo("진행 중")
        assertThat(view.type).isEqualTo("table")
        assertThat(view.columns).containsExactly(NotionViewColumn("status", "상태"), NotionViewColumn("title", null))
        assertThat(server.takeRequest().path).isEqualTo("/v1/views/view-1")
    }

    @Test
    fun `retains typed table options and per-column display settings`() {
        enqueueView(
            "table",
            """
            {"type":"table","wrap_cells":false,"frozen_column_index":2,"show_vertical_lines":false,
             "properties":[
               {"property_id":"title","visible":true,"width":320,"wrap":true},
               {"property_id":"notes","visible":true,"width":0,"wrap":false},
               {"property_id":"hidden","visible":false,"width":200}]}
            """,
        )

        val view = client().fetchDatabaseView("view-1")

        assertThat(view.configuration).isEqualTo(NotionViewConfiguration.Table(false, 2, false))
        assertThat(view.columns).containsExactly(
            NotionViewColumn("title", null, widthPixels = 320, wrap = true),
            NotionViewColumn("notes", null, widthPixels = 0, wrap = false),
        )
    }

    @Test
    fun `retains list layout separately from common property visibility`() {
        enqueueView("list", """{"type":"list","properties":[{"property_id":"title","visible":true,"wrap":false}]}""")

        val view = client().fetchDatabaseView("view-1")

        assertThat(view.configuration).isEqualTo(NotionViewConfiguration.ListView)
        assertThat(view.columns).containsExactly(NotionViewColumn("title", null, wrap = false))
    }

    @ParameterizedTest
    @ValueSource(strings = ["page_cover", "page_content", "property"])
    fun `retains each explicit gallery cover choice and card layout`(coverType: String) {
        val property = if (coverType == "property") ",\"property_id\":\"hidden-cover\"" else ""
        enqueueView(
            "gallery",
            """
            {"type":"gallery","properties":[{"property_id":"title","visible":true}],
             "cover":{"type":"$coverType"$property},"cover_size":"large","cover_aspect":"contain","card_layout":"compact"}
            """,
        )

        val view = client().fetchDatabaseView("view-1")

        val cover = when (coverType) {
            "page_cover" -> NotionGalleryCover.PageCover
            "page_content" -> NotionGalleryCover.PageContent
            else -> NotionGalleryCover.Property("hidden-cover")
        }
        assertThat(view.configuration).isEqualTo(
            NotionViewConfiguration.Gallery(cover, NotionGallerySize.LARGE, NotionGalleryAspect.CONTAIN, NotionGalleryLayout.COMPACT),
        )
        assertThat(view.columns).containsExactly(NotionViewColumn("title", null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ",\"wrap_cells\":null,\"frozen_column_index\":null,\"show_vertical_lines\":null"])
    fun `defaults omitted and cleared optional table settings without guessing visible properties`(options: String) {
        enqueueView("table", """{"type":"table","properties":null$options}""")

        val view = client().fetchDatabaseView("view-1")

        assertThat(view.configuration).isEqualTo(NotionViewConfiguration.Table())
        assertThat(view.columns).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ",\"cover\":null,\"cover_size\":null,\"cover_aspect\":null,\"card_layout\":null"])
    fun `defaults omitted and cleared gallery settings without selecting a cover source`(options: String) {
        enqueueView("gallery", """{"type":"gallery","properties":[]$options}""")

        val view = client().fetchDatabaseView("view-1")

        assertThat(view.configuration).isEqualTo(NotionViewConfiguration.Gallery())
        assertThat(view.columns).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["board", "calendar", "timeline", "chart", "map", "form", "dashboard", "future-layout"])
    fun `does not interpret excluded view configuration even when its payload is malformed`(type: String) {
        enqueueView(type, """{"type":false,"properties":"private-payload","cover_size":123}""")
        enqueueView(type, """"unrecognized-future-payload"""")
        val api = client()

        repeat(2) {
            val view = api.fetchDatabaseView("view-1")

            assertThat(view.type).isEqualTo(type)
            assertThat(view.columns).isNull()
            assertThat(view.configuration).isNull()
        }
    }

    @Test
    fun `defaults a known layout without inventing a missing data source or visible columns`() {
        enqueueJson(
            """
            {"object":"view","id":"view-1","parent":{"type":"database_id","database_id":"database-1"},"type":"table"}
            """,
        )

        val view = client().fetchDatabaseView("view-1")

        assertThat(view.columns).isNull()
        assertThat(view.configuration).isEqualTo(NotionViewConfiguration.Table())
        assertThat(view.dataSourceId).isNull()
        assertThat(view.name).isNotBlank()
    }

    @ParameterizedTest
    @ValueSource(strings = ["table", "list", "gallery"])
    fun `uses typed defaults for omitted and null configuration`(type: String) {
        val expected = when (type) {
            "table" -> NotionViewConfiguration.Table()
            "list" -> NotionViewConfiguration.ListView
            else -> NotionViewConfiguration.Gallery()
        }
        listOf("", ",\"configuration\":null").forEach { configuration ->
            enqueueJson(
                """
                {"object":"view","id":"view-1","parent":{"type":"database_id","database_id":"database-1"},
                 "type":"$type","data_source_id":"source-1"$configuration}
                """,
            )

            val view = client().fetchDatabaseView("view-1")

            assertThat(view.configuration).isEqualTo(expected)
            assertThat(view.columns).isNull()
            assertThat(view.dataSourceId).isEqualTo("source-1")
        }
    }

    @Test
    fun `keeps an explicitly empty visible-property set distinct from unavailable configuration`() {
        enqueueJson(
            """
            {"object":"view","id":"view-1","parent":{"type":"database_id","database_id":"database-1"},
             "name":"Empty","type":"table","configuration":{"type":"table","properties":[]}}
            """,
        )

        assertThat(client().fetchDatabaseView("view-1").columns).isEmpty()
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

    @Test
    fun `requests only visible row properties and retains the data source parent`() {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/row-1","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{}}
            """,
        )

        val row = client().fetchPage("row-1", listOf("title", "f%5C%5C"))

        assertThat(row.parent.dataSourceId).isEqualTo("source-1")
        assertThat(row.publicUrl).isEqualTo("https://workspace.notion.site/row-1")
        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameterValues("filter_properties[]")).containsExactly("title", "f%5C%5C")
        assertThat(request.requestUrl?.queryParameterNames).containsExactly("filter_properties[]")
    }

    @Test
    fun `retains optional page icon and cover media payload within the Notion adapter`() {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/row-1","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{},
             "icon":{"type":"emoji","emoji":"📚"},
             "cover":{"type":"file","file":{"url":"https://example.com/cover.jpg","expiry_time":"2026-09-01T00:00:00Z"}}}
            """,
        )

        val row = client().fetchPage("row-1")

        assertThat(row.icon?.get("emoji")?.stringValue()).isEqualTo("📚")
        assertThat(row.cover?.get("file")?.get("expiry_time")?.stringValue()).isEqualTo("2026-09-01T00:00:00Z")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ",\"icon\":null,\"cover\":null"])
    fun `allows absent and cleared page media without inventing a cover`(media: String) {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/row-1","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{}$media}
            """,
        )

        val row = client().fetchPage("row-1")

        assertThat(row.icon).isNull()
        assertThat(row.cover).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"icon\":1", "\"cover\":false"])
    fun `rejects malformed page media envelope without exposing its contents`(media: String) {
        enqueueJson(
            """
            {"object":"page","id":"row-1","parent":{"type":"data_source_id","data_source_id":"source-1"},
             "url":"https://app.notion.com/source-private-label","public_url":"https://workspace.notion.site/row-1",
             "in_trash":false,"last_edited_time":"2026-08-31T00:00:00Z","properties":{},$media}
            """,
        )

        assertThatThrownBy { client().fetchPage("row-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasMessageNotContaining("source-private-label")
    }

    @Test
    fun `returns one direct block page so a gallery cover reader can stop before fetching the next page`() {
        enqueueJson(
            """
            {"object":"list","results":[
              {"object":"block","id":"image-1","type":"image","has_children":false,"in_trash":false,
               "image":{"type":"external","external":{"url":"https://example.com/cover.jpg"},"caption":[]}}],
             "has_more":true,"next_cursor":"next-image-cursor"}
            """,
        )

        val page = client().fetchBlockChildrenPage("row-1", "image-cursor")

        assertThat(page.results.map { it.id }).containsExactly("image-1")
        assertThat(page.results.single().type).isEqualTo("image")
        assertThat(page.hasMore).isTrue()
        assertThat(page.nextCursor).isEqualTo("next-image-cursor")
        assertThat(server.requestCount).isEqualTo(1)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/v1/blocks/row-1/children")
        assertThat(request.requestUrl?.queryParameter("page_size")).isEqualTo("100")
        assertThat(request.requestUrl?.queryParameter("start_cursor")).isEqualTo("image-cursor")
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"type\":\"table\",\"properties\":{}}",
            "{\"type\":\"table\",\"properties\":[{\"property_id\":\"title\",\"visible\":\"true\"}]}",
            "{\"type\":\"table\",\"properties\":[{\"visible\":true}]}",
            "{\"type\":\"board\",\"properties\":[]}",
            "{\"type\":\"table\",\"properties\":[],\"wrap_cells\":\"true\"}",
            "{\"type\":\"table\",\"properties\":[],\"show_vertical_lines\":1}",
            "{\"type\":\"table\",\"properties\":[],\"frozen_column_index\":-1}",
            "{\"type\":\"table\",\"properties\":[],\"frozen_column_index\":1.5}",
            "{\"type\":\"table\",\"properties\":[],\"frozen_column_index\":2147483648}",
            "{\"type\":\"table\",\"properties\":[{\"property_id\":\"title\",\"visible\":true,\"width\":-1}]}",
            "{\"type\":\"table\",\"properties\":[{\"property_id\":\"title\",\"visible\":true,\"width\":1.5}]}",
            "{\"type\":\"table\",\"properties\":[{\"property_id\":\"title\",\"visible\":true,\"width\":\"300\"}]}",
            "{\"type\":\"table\",\"properties\":[{\"property_id\":\"title\",\"visible\":true,\"wrap\":\"true\"}]}",
        ],
    )
    fun `rejects malformed known view configuration instead of guessing visibility`(configuration: String) {
        enqueueJson(
            """
            {"object":"view","id":"view-1","parent":{"type":"database_id","database_id":"database-1"},
             "name":"source-private-label","type":"table","configuration":$configuration}
            """,
        )

        assertThatThrownBy { client().fetchDatabaseView("view-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasMessageNotContaining("source-private-label")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\"cover_size\":\"huge\"",
            "\"cover_size\":1",
            "\"cover_aspect\":\"stretch\"",
            "\"card_layout\":\"grid\"",
            "\"cover\":\"page_cover\"",
            "\"cover\":{}",
            "\"cover\":{\"type\":\"other\"}",
            "\"cover\":{\"type\":\"property\"}",
            "\"cover\":{\"type\":\"property\",\"property_id\":\"\"}",
        ],
    )
    fun `rejects malformed gallery options instead of guessing a different layout or cover`(option: String) {
        enqueueView("gallery", """{"type":"gallery","properties":[],$option}""")

        assertThatThrownBy { client().fetchDatabaseView("view-1") }
            .isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `uses the existing safe access error for inaccessible databases`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("private database details"))

        assertThatThrownBy { client().fetchDatabase("database-1") }
            .isInstanceOf(SourceAccessException::class.java)
            .hasMessageNotContaining("private database details")
    }

    private fun client(): NotionApiClient = NotionApiClient(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        token = "test-token",
        requestTimeout = Duration.ofSeconds(1),
        collectionTimeout = Duration.ofSeconds(2),
    )

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.trimIndent()))
    }

    private fun enqueueView(type: String, configuration: String) {
        enqueueJson(
            """
            {"object":"view","id":"view-1","parent":{"type":"database_id","database_id":"database-1"},
             "name":"보기","type":"$type","data_source_id":"source-1","configuration":$configuration}
            """,
        )
    }
}
