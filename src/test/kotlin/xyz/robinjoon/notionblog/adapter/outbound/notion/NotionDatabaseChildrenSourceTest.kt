package xyz.robinjoon.notionblog.adapter.outbound.notion

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
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class NotionDatabaseChildrenSourceTest {
    private val server = MockWebServer()
    private val sourceId = SourceId("notion-main")
    private val responses = mutableMapOf<String, String>()
    private val deniedPaths = mutableSetOf<String>()
    private var onRequest: (String) -> Unit = {}
    private val queryPages = mutableMapOf<Pair<String, String?>, String>()

    @BeforeEach
    fun startServer() {
        server.start()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    onRequest(path)
                    if (path in
                        deniedPaths
                    ) {
                        return MockResponse().setResponseCode(404).setBody("""{"object":"error","code":"object_not_found"}""")
                    }
                    val body =
                        if (path.endsWith("/query")) {
                            val cursor = Regex("\"start_cursor\":\"([^\"]+)\"").find(request.body.readUtf8())?.groupValues?.get(1)
                            queryPages[path to cursor]
                        } else {
                            responses[path]
                        }
                    return body?.let { MockResponse().setHeader("Content-Type", "application/json").setBody(it) }
                        ?: MockResponse().setResponseCode(404)
                }
            }
        responses["/v1/pages/$ROOT"] = page(ROOT, parent("workspace"))
        responses["/v1/blocks/$ROOT/children"] = list(databaseBlock())
        responses["/v1/databases/$DATABASE"] = database()
        responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE)
        responses["/v1/views"] = viewsList("""{"object":"view","id":"$VIEW"}""")
        responses["/v1/views/$VIEW"] = view("board")
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW))
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `discovers every owned source page independently of a filtered display view including unpublished pages`() {
        responses["/v1/databases/$DATABASE"] = database(sources = listOf(SOURCE, SECOND_SOURCE))
        responses["/v1/data_sources/$SECOND_SOURCE"] = dataSource(SECOND_SOURCE)
        responses["/v1/views/$VIEW"] = view("table")
        responses["/v1/views/$VIEW/queries"] =
            """{"object":"view_query","id":"$QUERY","view_id":"$VIEW",
              "results":[{"object":"page","id":"$ROW"}],"has_more":false,"next_cursor":null}"""
        responses["/v1/pages/$ROW"] = page(ROW)
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW), page(PRIVATE_ROW, publicUrl = "null"), cursor = "more")
        queryPages["/v1/data_sources/$SOURCE/query" to "more"] = list(page(HIDDEN_ROW), page(TRASH_ROW, inTrash = true))
        queryPages["/v1/data_sources/$SECOND_SOURCE/query" to null] = list(page(SECOND_ROW, parent("data_source_id", SECOND_SOURCE)))

        val imported = source().fetch(reference())

        assertThat(imported.containedChildren).containsExactlyElementsOf(
            listOf(ROW, PRIVATE_ROW, HIDDEN_ROW, SECOND_ROW).map {
                SourceDocumentRef(sourceId, it)
            },
        )
        val display =
            imported.content.roots
                .flatMap(::nodes)
                .mapNotNull { it.content as? DataViewContent }
                .single()
        assertThat(display.data.rows).hasSize(1)
        assertThat(
            display.data.rows
                .single()
                .cells
                .single()
                .toString(),
        ).contains(ROW).doesNotContain(PRIVATE_ROW, HIDDEN_ROW)
    }

    @ParameterizedTest
    @ValueSource(strings = ["board", "calendar", "future"])
    fun `discovers owned pages even when every display view is unsupported`(viewType: String) {
        responses["/v1/views/$VIEW"] = view(viewType)

        val imported = source().fetch(reference())

        assertThat(imported.containedChildren).containsExactly(SourceDocumentRef(sourceId, ROW))
        assertThat(imported.content.roots).isEmpty()
    }

    @Test
    fun `discovers owned pages when no display view exists`() {
        responses["/v1/views"] = viewsList()

        assertThat(source().fetch(reference()).containedChildren).containsExactly(SourceDocumentRef(sourceId, ROW))
    }

    @Test
    fun `keeps structural children when display views are inaccessible`() {
        deniedPaths += "/v1/views"

        assertThat(source().fetch(reference()).containedChildren).containsExactly(SourceDocumentRef(sourceId, ROW))
    }

    @ParameterizedTest
    @ValueSource(strings = ["page_id", "workspace", "data_source_id"])
    fun `does not discover pages from a database owned outside the current page`(type: String) {
        responses["/v1/databases/$DATABASE"] = database(parent(type, FOREIGN))

        assertThat(source().fetch(reference()).containedChildren).isEmpty()
        assertThat(paths()).doesNotContain("/v1/data_sources/$SOURCE/query")
    }

    @ParameterizedTest
    @ValueSource(strings = ["database_id", "data_source_id"])
    fun `does not discover pages from a linked source owned elsewhere`(type: String) {
        responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE, parent(type, FOREIGN))

        assertThat(source().fetch(reference()).containedChildren).isEmpty()
        assertThat(paths()).doesNotContain("/v1/data_sources/$SOURCE/query")
    }

    @ParameterizedTest
    @ValueSource(strings = ["page_id", "workspace", "block_id"])
    fun `rejects parent shapes that cannot own a data source`(type: String) {
        responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE, parent(type, FOREIGN))

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["database", "ancestor-database", "ancestor-workspace"])
    fun `rejects known parent kinds that cannot own the database or its ancestor block`(stage: String) {
        if (stage == "database") {
            responses["/v1/databases/$DATABASE"] = database(parent("database_id", FOREIGN))
        } else {
            responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
            val parentType = if (stage == "ancestor-database") "database_id" else "workspace"
            responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent(parentType, FOREIGN))
        }

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `verifies real block ancestors before including pages of a nested database`() {
        responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
        responses["/v1/blocks/$ROOT/children"] = list(containerBlock())
        responses["/v1/blocks/$CONTAINER/children"] = list(databaseBlock())
        responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("page_id", ROOT))

        assertThat(source().fetch(reference()).containedChildren).containsExactly(SourceDocumentRef(sourceId, ROW))
        assertThat(paths()).contains("/v1/blocks/$CONTAINER")
    }

    @Test
    fun `does not mistake a visited external synced ancestor for structural ownership`() {
        responses["/v1/blocks/$ROOT/children"] =
            list(
                """{"id":"$SYNCED","type":"synced_block","has_children":true,"in_trash":false,
                  "synced_block":{"synced_from":{"type":"block_id","block_id":"$CONTAINER"}}}""",
            )
        responses["/v1/blocks/$SYNCED/children"] = list()
        responses["/v1/blocks/$CONTAINER/children"] = list(databaseBlock())
        responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
        responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("page_id", FOREIGN))

        assertThat(source().fetch(reference()).containedChildren).isEmpty()
        assertThat(paths()).contains("/v1/blocks/$CONTAINER").doesNotContain("/v1/data_sources/$SOURCE/query")
    }

    @ParameterizedTest
    @ValueSource(strings = ["metadata", "source", "query", "ancestor"])
    fun `fails the containing post when structural metadata or enumeration is inaccessible`(stage: String) {
        val denied =
            when (stage) {
                "metadata" -> {
                    "/v1/databases/$DATABASE"
                }

                "source" -> {
                    "/v1/data_sources/$SOURCE"
                }

                "query" -> {
                    "/v1/data_sources/$SOURCE/query"
                }

                else -> {
                    responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
                    "/v1/blocks/$CONTAINER"
                }
            }
        deniedPaths += denied

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceAccessException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["database", "source", "ancestor"])
    fun `fails unsupported ownership instead of silently shrinking children`(stage: String) {
        when (stage) {
            "database" -> {
                responses["/v1/databases/$DATABASE"] = database(parent("future_parent"))
            }

            "source" -> {
                responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE, parent("future_parent"))
            }

            else -> {
                responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
                responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("future_parent"))
            }
        }

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["database", "source", "ancestor"])
    fun `rejects malformed foreign parent ids instead of excluding ownership`(stage: String) {
        when (stage) {
            "database" -> {
                responses["/v1/databases/$DATABASE"] = database(parent("database_id", "not-an-id"))
            }

            "source" -> {
                responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE, parent("data_source_id", "not-an-id"))
            }

            else -> {
                responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
                responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("data_source_id", "not-an-id"))
            }
        }

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `fails a parent cycle instead of accepting a database ancestor`() {
        responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
        responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("block_id", SYNCED))
        responses["/v1/blocks/$SYNCED"] = blockMetadata(SYNCED, parent("block_id", CONTAINER))

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `fails when proving ownership exceeds the configured depth`() {
        responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
        responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("block_id", SYNCED))
        responses["/v1/blocks/$SYNCED"] = blockMetadata(SYNCED, parent("page_id", ROOT))

        assertThatThrownBy { source(maxDepth = 1).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `fails when owned page discovery exceeds the existing work budget`() {
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW), page(PRIVATE_ROW), page(HIDDEN_ROW))

        assertThatThrownBy { source(maxBlockCount = 3).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["foreign-parent", "duplicate-row", "duplicate-cursor", "incomplete"])
    fun `rejects incomplete or inconsistent structural enumeration`(problem: String) {
        when (problem) {
            "foreign-parent" -> {
                queryPages["/v1/data_sources/$SOURCE/query" to null] =
                    list(page(ROW, parent("data_source_id", SECOND_SOURCE)))
            }

            "duplicate-row" -> {
                queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW), page(ROW))
            }

            "duplicate-cursor" -> {
                queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW), cursor = "again")
                queryPages["/v1/data_sources/$SOURCE/query" to "again"] = list(page(PRIVATE_ROW), cursor = "again")
            }

            else -> {
                queryPages["/v1/data_sources/$SOURCE/query" to null] =
                    """{"object":"list","results":[],"has_more":true,"next_cursor":null}"""
            }
        }

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceException::class.java)
    }

    @Test
    fun `fails instead of returning partial children when a later structural page is inaccessible`() {
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW), cursor = "unavailable")

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceAccessException::class.java)
    }

    @Test
    fun `checks the containing post deadline after structural enumeration`() {
        val time = AtomicLong()
        onRequest = { path -> if (path == "/v1/data_sources/$SOURCE/query") time.set(Duration.ofSeconds(5).toNanos()) }

        assertThatThrownBy { source(nanoTime = time::get).fetch(reference()) }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `excludes a trashed source before querying its pages`() {
        responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE).replace("\"in_trash\":false", "\"in_trash\":true")

        assertThat(source().fetch(reference()).containedChildren).isEmpty()
        assertThat(paths()).doesNotContain("/v1/data_sources/$SOURCE/query")
    }

    @Test
    fun `rejects a queried page whose id is the containing page`() {
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROOT))

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects a page repeated by separate owned sources`() {
        responses["/v1/databases/$DATABASE"] = database(sources = listOf(SOURCE, SECOND_SOURCE))
        responses["/v1/data_sources/$SECOND_SOURCE"] = dataSource(SECOND_SOURCE)
        queryPages["/v1/data_sources/$SECOND_SOURCE/query" to null] = list(page(ROW, parent("data_source_id", SECOND_SOURCE)))

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `validates the structural parent even for a trashed query row`() {
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW, parent("data_source_id", SECOND_SOURCE), inTrash = true))

        assertThatThrownBy { source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    private fun database(
        parent: String = parent("page_id", ROOT),
        sources: List<String> = listOf(SOURCE),
    ) = """{"object":"database","id":"$DATABASE","parent":$parent,"data_sources":[${sources.joinToString(
        ",",
    ) {
        """{"id":"$it","name":"Posts"}"""
    }}],"title":[{"plain_text":"Posts"}],"url":"https://www.notion.so/$DATABASE","in_trash":false}"""

    private fun dataSource(
        id: String,
        parent: String = parent("database_id", DATABASE),
    ) = """{"object":"data_source","id":"$id","parent":$parent,"in_trash":false,
          "properties":{"Name":{"id":"title","name":"Name","type":"title"}}}"""

    private fun page(
        id: String,
        parent: String = parent("data_source_id", SOURCE),
        publicUrl: String = "\"https://site.notion.site/$id\"",
        inTrash: Boolean = false,
    ) = """{"object":"page","id":"$id","parent":$parent,"url":"https://www.notion.so/$id",
          "public_url":$publicUrl,"in_trash":$inTrash,"last_edited_time":"2026-10-05T00:00:00Z",
          "properties":{"Name":{"id":"title","type":"title",
            "title":[{"type":"text","text":{"content":"$id"},"plain_text":"$id","annotations":{}}]}}}"""

    private fun parent(
        type: String,
        id: String = ROOT,
    ) = if (type ==
        "workspace"
    ) {
        """{"type":"workspace","workspace":true}"""
    } else {
        """{"type":"$type","$type":"$id"}"""
    }

    private fun databaseBlock() =
        """{"id":"$DATABASE","type":"child_database","has_children":true,"in_trash":false,"child_database":{"title":"Posts"}}"""

    private fun containerBlock() = """{"id":"$CONTAINER","type":"toggle","has_children":true,"in_trash":false,"toggle":{"rich_text":[]}}"""

    private fun blockMetadata(
        id: String,
        parent: String,
    ) = """{"object":"block","id":"$id","parent":$parent,"type":"toggle","has_children":true,"in_trash":false,"toggle":{"rich_text":[]}}"""

    private fun view(type: String) =
        """{"object":"view","id":"$VIEW","parent":{"type":"database_id","database_id":"$DATABASE"},
          "name":"Shown","type":"$type","data_source_id":"$SOURCE"}"""

    private fun viewsList(vararg entries: String) =
        """{"object":"list","type":"view","view":{},"results":[${entries.joinToString(",")}],"has_more":false,"next_cursor":null}"""

    private fun list(
        vararg entries: String,
        cursor: String? = null,
    ) = """{"object":"list","results":[${entries.joinToString(
        ",",
    )}],"has_more":${cursor != null},"next_cursor":${cursor?.let { "\"$it\"" } ?: "null"}}"""

    private fun reference() = SourceDocumentRef(sourceId, ROOT)

    private fun source(
        maxDepth: Int = 8,
        maxBlockCount: Int = 100,
        nanoTime: () -> Long = System::nanoTime,
    ) = NotionPostSource(
        sourceId,
        NotionApiClient(server.url("/v1").toString().trimEnd('/'), "test-token", Duration.ofSeconds(1), Duration.ofSeconds(5)),
        maxDepth = maxDepth,
        maxBlockCount = maxBlockCount,
        collectionTimeout = Duration.ofSeconds(5),
        nanoTime = nanoTime,
    )

    private fun paths() = List(server.requestCount) { server.takeRequest().requestUrl?.encodedPath }

    private fun nodes(node: BlockNode): List<BlockNode> = listOf(node) + node.children.flatMap(::nodes)

    private companion object {
        const val ROOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATABASE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SOURCE = "cccccccccccccccccccccccccccccccc"
        const val SECOND_SOURCE = "dddddddddddddddddddddddddddddddd"
        const val VIEW = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val QUERY = "ffffffffffffffffffffffffffffffff"
        const val ROW = "11111111111111111111111111111111"
        const val PRIVATE_ROW = "22222222222222222222222222222222"
        const val HIDDEN_ROW = "33333333333333333333333333333333"
        const val TRASH_ROW = "44444444444444444444444444444444"
        const val SECOND_ROW = "55555555555555555555555555555555"
        const val FOREIGN = "66666666666666666666666666666666"
        const val CONTAINER = "77777777777777777777777777777777"
        const val SYNCED = "88888888888888888888888888888888"
    }
}
