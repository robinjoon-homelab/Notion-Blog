package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.DATABASE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.ROOT
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.SOURCE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.dataSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.database
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.databaseBlock
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.list
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.page
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.parent
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.sourceId
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.view
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.viewsList
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostSource
import java.time.Duration

internal class NotionDatabaseChildrenServer {
    private val server = MockWebServer()
    val responses = mutableMapOf<String, String>()
    val deniedPaths = mutableSetOf<String>()
    var onRequest: (String) -> Unit = {}
    val queryPages = mutableMapOf<Pair<String, String?>, String>()

    fun start() {
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

    fun stop() {
        server.shutdown()
    }

    fun source(
        maxDepth: Int = 8,
        maxBlockCount: Int = 100,
        nanoTime: () -> Long = { 0L },
    ) = NotionPostSource(
        sourceId,
        NotionApiClient(server.url("/v1").toString().trimEnd('/'), "test-token", Duration.ofSeconds(1), Duration.ofSeconds(5)),
        maxDepth = maxDepth,
        maxBlockCount = maxBlockCount,
        collectionTimeout = Duration.ofSeconds(5),
        nanoTime = nanoTime,
    )
}
