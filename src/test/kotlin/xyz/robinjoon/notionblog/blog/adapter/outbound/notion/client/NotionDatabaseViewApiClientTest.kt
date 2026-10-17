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
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionGalleryAspect
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionGalleryLayout
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionGallerySize
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import java.time.Duration

class NotionDatabaseViewApiClientTest {
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

        val cover =
            when (coverType) {
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
        val expected =
            when (type) {
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

    private fun enqueueView(
        type: String,
        configuration: String,
    ) {
        enqueueJson(
            """
            {"object":"view","id":"view-1","parent":{"type":"database_id","database_id":"database-1"},
             "name":"보기","type":"$type","data_source_id":"source-1","configuration":$configuration}
            """,
        )
    }
}
