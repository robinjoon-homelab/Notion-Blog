package xyz.robinjoon.notionblog.adapter.output.notion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.adapter.input.web.PostPageViewAssembler
import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.application.model.BlogPage
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.PresentationTokens
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class NotionDefaultDatabaseViewTest {
    private val server = MockWebServer()
    private val sourceId = SourceId("notion-main")

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @ParameterizedTest
    @ValueSource(strings = ["table", "list", "gallery"])
    fun `imports default view responses with only the schema title and published rows`(type: String) {
        val cover = if (type == "gallery") """, "cover":{"type":"page_cover"}""" else ""
        fixture(type, """, "configuration":{"type":"$type"$cover}""")

        val imported = source().fetch(SourceDocumentRef(sourceId, ROOT))
        val view = dataView(imported)

        assertLayout(view, type)
        assertThat(view.data.columns.map { it.name }).containsExactly("Name")
        assertThat(view.data.titleColumnIndex).isZero()
        assertThat(view.data.rows).hasSize(1)
        assertThat(view.data.rows.single().cells.single()).containsExactly(InlineContent.Text("Public row", link = LinkTarget.ExternalUrl(URI("https://site.notion.site/$ROW"))))
        assertThat(view.data.toString()).doesNotContain("Private row", "Trash row", "Hidden value", "Status", "Done")
        assertThat(imported.containedChildren).isEmpty()
        if (type == "gallery") {
            assertThat(view.data.rows.single().cover).isEqualTo(MediaSource.External(URI(COVER)))
        } else {
            assertThat(view.data.rows.single().cover).isNull()
        }
        assertRequestedProperties("title")

        val html = render(imported)
        val layoutClass = if (type == "table") "notion-data-table" else "notion-data-$type"
        assertThat(html).contains(layoutClass, "Public row", "Default view")
            .doesNotContain("Unsupported block", "Private row", "Trash row", "Hidden value", "Status", "Done")
        if (type == "gallery") assertThat(html).contains("src=\"$COVER\"", "alt=\"Public row\"")
        val path = Path.of("build", "qa", "default-database-$type.html")
        Files.createDirectories(path.parent)
        Files.writeString(path, html)
        checkNotNull(javaClass.classLoader.getResourceAsStream("qa/preview-cover.svg")).use {
            Files.copy(it, path.parent.resolve("preview-cover.svg"), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "table, missing",
        "table, null",
        "table, null-properties",
        "list, missing",
        "list, null",
        "list, null-properties",
        "gallery, missing",
        "gallery, null",
        "gallery, null-properties",
    )
    fun `imports missing and cleared custom configuration without revealing other properties`(type: String, configuration: String) {
        val field = when (configuration) {
            "missing" -> ""
            "null" -> """, "configuration":null"""
            else -> """, "configuration":{"type":"$type","properties":null}"""
        }
        fixture(type, field)

        val view = dataView(source().fetch(SourceDocumentRef(sourceId, ROOT)))

        assertLayout(view, type)
        assertThat(view.data.columns.map { it.name }).containsExactly("Name")
        assertThat(view.data.rows.single().cells.single()).containsExactly(InlineContent.Text("Public row", link = LinkTarget.ExternalUrl(URI("https://site.notion.site/$ROW"))))
        assertThat(view.data.rows.single().cover).isNull()
        assertThat(view.data.toString()).doesNotContain("Hidden value", "Status", "Done")
        assertRequestedProperties("title")
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["false", "123", "{}", "[]", "\"\"", "\"   \""])
    fun `fails the containing post instead of returning collected rows when a later row has invalid publication metadata`(publicUrl: String?) {
        fixture("table", "", privateRowPublicUrl = publicUrl)

        assertThatThrownBy { source().fetch(SourceDocumentRef(sourceId, ROOT)) }
            .isInstanceOf(SourceConfigurationException::class.java)

        assertThat(requests().map { it.requestUrl?.encodedPath })
            .containsSubsequence("/v1/pages/$ROW", "/v1/pages/$PRIVATE_ROW")
            .doesNotContain("/v1/pages/$TRASH_ROW")
    }

    @Test
    fun `honors explicit visibility without restoring a hidden title or unspecified properties`() {
        fixture(
            "list",
            """, "configuration":{"type":"list","properties":[
              {"property_id":"title","visible":false},
              {"property_id":"status","visible":true},
              {"property_id":"secret","visible":false},
              {"property_id":"unknown-visibility"}]}
            """,
        )

        val view = dataView(source().fetch(SourceDocumentRef(sourceId, ROOT)))

        assertThat(view.data.columns.map { it.name }).containsExactly("Status")
        assertThat(view.data.titleColumnIndex).isNull()
        assertThat(view.data.rows.single().cells.single()).containsExactly(InlineContent.Text("Done"))
        assertThat(view.data.toString()).doesNotContain("Public row", "Hidden value", "Unspecified value")
        assertRequestedProperties("status")
    }

    @Test
    fun `renders a default table with an empty saved query as an empty view instead of unsupported`() {
        fixture("table", """, "configuration":{"type":"table"}""", emptyRows = true)

        val imported = source().fetch(SourceDocumentRef(sourceId, ROOT))

        val view = dataView(imported)
        assertThat(view).isInstanceOf(DataViewContent.Table::class.java)
        assertThat(view.data.columns.map { it.name }).containsExactly("Name")
        assertThat(view.data.rows).isEmpty()
        assertThat(render(imported)).contains("No published rows in this view.").doesNotContain("Unsupported block")
        assertThat(requests().map { it.requestUrl?.encodedPath })
            .contains("/v1/views/$VIEW/queries")
            .doesNotContain("/v1/pages/$ROW", "/v1/pages/$PRIVATE_ROW", "/v1/pages/$TRASH_ROW")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "[]",
            "[{\"property_id\":\"title\",\"visible\":false},{\"property_id\":\"status\",\"visible\":false}]",
            "[{\"property_id\":\"title\"}]",
        ],
    )
    fun `keeps explicitly empty visible properties unavailable without querying rows`(properties: String) {
        fixture("table", """, "configuration":{"type":"table","properties":$properties}""")

        val imported = source().fetch(SourceDocumentRef(sourceId, ROOT))

        assertThat(imported.content.roots.flatMap(::nodes).map { it.content })
            .contains(UnsupportedBlockContent("database_view"))
            .noneMatch { it is DataViewContent }
        val paths = requests().map { it.requestUrl?.encodedPath }
        assertThat(paths).doesNotContain("/v1/views/$VIEW/queries", "/v1/pages/$ROW", "/v1/pages/$PRIVATE_ROW", "/v1/pages/$TRASH_ROW")
    }

    private fun assertLayout(view: DataViewContent, type: String) {
        val expected = when (type) {
            "table" -> DataViewContent.Table::class.java
            "list" -> DataViewContent.ListView::class.java
            else -> DataViewContent.Gallery::class.java
        }
        assertThat(view).isInstanceOf(expected)
    }

    private fun assertRequestedProperties(vararg propertyIds: String) {
        val requests = requests()
        val rows = requests.filter { it.requestUrl?.encodedPath in setOf("/v1/pages/$ROW", "/v1/pages/$PRIVATE_ROW", "/v1/pages/$TRASH_ROW") }
        assertThat(rows).hasSize(3)
        rows.forEach { request ->
            assertThat(request.requestUrl?.queryParameterValues("filter_properties[]")).containsExactly(*propertyIds)
        }
        val query = requests.single { it.requestUrl?.encodedPath == "/v1/views/$VIEW/queries" }
        assertThat(query.method).isEqualTo("POST")
        assertThat(query.body.readUtf8()).isEqualTo("{\"page_size\":100}")
        assertThat(requests.map { it.requestUrl?.encodedPath }).doesNotContain("/v1/data_sources/$DATA_SOURCE/query")
    }

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    private fun dataView(imported: ImportedPost): DataViewContent {
        val contents = imported.content.roots.flatMap(::nodes).map { it.content }
        assertThat(contents).noneMatch { it is UnsupportedBlockContent }
        return contents.filterIsInstance<DataViewContent>().single()
    }

    private fun nodes(node: BlockNode): List<BlockNode> = listOf(node) + node.children.flatMap(::nodes)

    private fun render(imported: ImportedPost): String {
        val assets = listOf(
            "/presentation/notion/v1/notion.css",
            "/presentation/notion/enhancements/v1/notion-enhancements.css",
            "/presentation/notion/database/v3/notion-database.css",
            "/presentation/notion/v1/notion.js",
            "/presentation/notion/database/v2/notion-database.js",
        ).associate { path ->
            val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("static$path")).use { it.readBytes() }
            val integrity = "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            val reference = PresentationAssetRef(
                path.substringAfterLast('/'),
                if (path.contains("/v3/")) {
                    3
                } else if (path.contains("/v2/")) {
                    2
                } else {
                    1
                },
                integrity,
            )
            reference to PresentationAssetDescriptor(path, if (path.endsWith(".css")) "text/css" else "text/javascript", integrity)
        }
        val profile = PresentationProfile(
            PresentationProfileId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            PresentationProfileKey("default"),
            1,
            PresentationTokens(),
            assets.filterValues { it.mediaType == "text/css" }.keys.toList(),
            assets.filterValues { it.mediaType == "text/javascript" }.keys.toList(),
        )
        val page = BlogPage(
            SiteConfiguration(
                PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                imported.sourceDocument,
                null,
                null,
                SiteMetadata("Default database views", null, "en", null),
                PresentationProfileRef(profile.id, profile.version),
            ),
            profile,
            assets,
            Post(PostId(UUID.fromString("00000000-0000-0000-0000-000000000003")), imported.title, imported.content),
            null,
            null,
            emptyMap(),
        )
        val assembler = PostPageViewAssembler(Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC))
        val engine = SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    templateMode = TemplateMode.HTML
                    characterEncoding = "UTF-8"
                    isCacheable = false
                },
            )
        }
        return engine.process("blog/post", Context().apply { setVariable("page", assembler.assemble(page)) })
    }

    private fun source() = NotionPostSource(
        sourceId,
        NotionApiClient(server.url("/v1").toString().trimEnd('/'), "test-token", Duration.ofSeconds(1), Duration.ofSeconds(5)),
        maxDepth = 8,
        maxBlockCount = 100,
        collectionTimeout = Duration.ofSeconds(5),
    )

    private fun fixture(type: String, configurationField: String, emptyRows: Boolean = false, privateRowPublicUrl: String? = "null") {
        val rows = if (emptyRows) "" else """{"object":"page","id":"$ROW"},{"object":"page","id":"$PRIVATE_ROW"},{"object":"page","id":"$TRASH_ROW"}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.encodedPath) {
                    "/v1/pages/$ROOT" -> page(ROOT, "Root", root = true)

                    "/v1/blocks/$ROOT/children" ->
                        """{"results":[
                      {"id":"$DATABASE","type":"child_database","has_children":true,"in_trash":false,"child_database":{"title":"Projects"}}
                    ],"has_more":false,"next_cursor":null}"""

                    "/v1/databases/$DATABASE" ->
                        """{"object":"database","id":"$DATABASE","title":[{"plain_text":"Projects"}],
                      "url":"https://www.notion.so/$DATABASE","public_url":null,"in_trash":false}"""

                    "/v1/views" -> """{"object":"list","type":"view","view":{},"results":[{"object":"view","id":"$VIEW"}],"has_more":false,"next_cursor":null}"""

                    "/v1/views/$VIEW" ->
                        """{"object":"view","id":"$VIEW","parent":{"type":"database_id","database_id":"$DATABASE"},
                      "name":"Default view","type":"$type","data_source_id":"$DATA_SOURCE"$configurationField}"""

                    "/v1/data_sources/$DATA_SOURCE" ->
                        """{"object":"data_source","id":"$DATA_SOURCE","properties":{
                      "Hidden":{"id":"secret","name":"Hidden","type":"rich_text"},
                      "Status":{"id":"status","name":"Status","type":"status"},
                      "Name":{"id":"title","name":"Name","type":"title"},
                      "Unspecified":{"id":"unknown-visibility","name":"Unspecified","type":"rich_text"}}}"""

                    "/v1/views/$VIEW/queries" ->
                        """{"object":"view_query","id":"$QUERY","view_id":"$VIEW","results":[$rows],
                      "has_more":false,"next_cursor":null}"""

                    "/v1/pages/$ROW" -> page(ROW, "Public row")

                    "/v1/pages/$PRIVATE_ROW" -> page(PRIVATE_ROW, "Private row", published = false).let { response ->
                        if (privateRowPublicUrl == null) {
                            response.replace("\"public_url\":null,", "")
                        } else {
                            response.replace("\"public_url\":null", "\"public_url\":$privateRowPublicUrl")
                        }
                    }

                    "/v1/pages/$TRASH_ROW" -> page(TRASH_ROW, "Trash row", inTrash = true)

                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
    }

    private fun page(id: String, title: String, root: Boolean = false, published: Boolean = true, inTrash: Boolean = false): String {
        val parent = if (root) """{"type":"workspace"}""" else """{"type":"data_source_id","data_source_id":"$DATA_SOURCE"}"""
        val publicUrl = if (published) """"https://site.notion.site/$id"""" else "null"
        return """{"id":"$id","parent":$parent,"url":"https://www.notion.so/$id","public_url":$publicUrl,
          "in_trash":$inTrash,"last_edited_time":"2026-08-31T00:00:00Z", "properties":{
            "Name":{"id":"title","type":"title","title":[{"type":"text","plain_text":"$title","text":{"content":"$title"},"annotations":{}}]},
            "Status":{"id":"status","type":"status","status":{"name":"Done"}},
            "Hidden":{"id":"secret","type":"rich_text","rich_text":[{"type":"text","text":{"content":"Hidden value"},"annotations":{}}]},
            "Unspecified":{"id":"unknown-visibility","type":"rich_text","rich_text":[{"type":"text","text":{"content":"Unspecified value"},"annotations":{}}]}},
          "cover":{"type":"external","external":{"url":"$COVER"}}}"""
    }

    private companion object {
        const val ROOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATABASE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val VIEW = "cccccccccccccccccccccccccccccccc"
        const val DATA_SOURCE = "dddddddddddddddddddddddddddddddd"
        const val QUERY = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val ROW = "11111111111111111111111111111111"
        const val PRIVATE_ROW = "22222222222222222222222222222222"
        const val TRASH_ROW = "33333333333333333333333333333333"
        const val COVER = "http://localhost:8081/preview-cover.svg"
    }
}
