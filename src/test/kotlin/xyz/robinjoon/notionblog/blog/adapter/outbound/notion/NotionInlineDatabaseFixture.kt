package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import io.mockk.every
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewQueryResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import java.time.Duration

internal object NotionInlineDatabaseFixture {
    const val ROOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val DATABASE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val VIEW = "cccccccccccccccccccccccccccccccc"
    const val SECOND_VIEW = "dddddddddddddddddddddddddddddddd"
    const val DATA_SOURCE = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    const val QUERY = "ffffffffffffffffffffffffffffffff"
    const val ROW = "11111111111111111111111111111111"
    const val SECOND_ROW = "22222222222222222222222222222222"
    const val PRIVATE_ROW = "33333333333333333333333333333333"
    const val TRASH_ROW = "44444444444444444444444444444444"
    const val SYNCED_ORIGIN = "55555555555555555555555555555555"
    const val SYNCED_REFERENCE = "66666666666666666666666666666666"

    val json = JsonMapper.builder().build()
    val sourceId = SourceId("notion-main")

    fun stubDefaults(client: NotionApiClient) {
        every { client.fetchPage(ROOT) } returns
            NotionPageResponse(
                ROOT,
                NotionPageParentResponse("workspace", null),
                "https://www.notion.so/$ROOT",
                "https://site.notion.site/$ROOT",
                false,
                "2026-08-31T00:00:00Z",
                json.readTree("""{"Name":{"type":"title","title":[{"plain_text":"Root"}]}}"""),
            )
        every { client.fetchDirectBlockChildren(ROOT) } returns
            listOf(NotionBlockEnvelope(DATABASE, "child_database", true, false, json.readTree("""{"title":"Projects"}""")))
        every { client.fetchDatabase(DATABASE) } returns database()
        every { client.fetchDatabaseViews(DATABASE, null) } returns pageOf(listOf(VIEW))
        every { client.fetchDatabaseView(VIEW) } returns view()
        every { client.fetchDataSource(DATA_SOURCE) } returns
            NotionDataSourceResponse(
                DATA_SOURCE,
                listOf(
                    NotionDatabaseProperty("secret", "Hidden", "rich_text"),
                    NotionDatabaseProperty("status", "Status", "status"),
                    NotionDatabaseProperty("title", "Name", "title"),
                ),
                NotionParentResponse.Database(SECOND_VIEW),
                false,
            )
        every { client.createViewQuery(VIEW) } returns query(listOf(ROW))
        every { client.fetchPage(ROW, listOf("title", "status")) } returns row(ROW)
    }

    fun database() =
        NotionDatabaseResponse(DATABASE, "Projects", "https://www.notion.so/$DATABASE", false, NotionParentResponse.Workspace, emptyList())

    fun view(id: String = VIEW) =
        NotionDatabaseViewResponse(
            id,
            DATABASE,
            "Published projects",
            "table",
            DATA_SOURCE,
            listOf(NotionViewColumn("title", null), NotionViewColumn("status", null)),
            configuration = NotionViewConfiguration.Table(),
        )

    fun query(
        ids: List<String>,
        cursor: String? = null,
        viewId: String = VIEW,
    ) = NotionViewQueryResponse(QUERY, viewId, pageOf(ids, cursor))

    fun pageOf(
        ids: List<String>,
        cursor: String? = null,
    ) = NotionPaginationResponse(ids, cursor != null, cursor)

    fun row(
        id: String,
        name: String = "First",
    ) = NotionPageResponse(
        id,
        NotionPageParentResponse("data_source_id", null, DATA_SOURCE),
        "https://www.notion.so/$id",
        "https://site.notion.site/$id",
        false,
        "2026-08-31T00:00:00Z",
        json.readTree(
            """{
          "Old name":{"id":"title","type":"title","title":[{"type":"text","text":{"content":"$name"},"plain_text":"$name",
            "annotations":{"bold":false,"italic":false,"strikethrough":false,"underline":false,"code":false,"color":"default"}}]},
          "State":{"id":"status","type":"status","status":{"name":"Done","color":"green"}},
          "Hidden":{"id":"secret","type":"rich_text","rich_text":[{"type":"text","text":{"content":"Hidden secret"}}]}
        }""",
        ),
    )

    fun reference() = SourceDocumentRef(sourceId, ROOT)

    fun source(
        client: NotionApiClient,
        maxBlockCount: Int = 100,
        maxDepth: Int = 8,
        nanoTime: () -> Long = { 0L },
    ) = NotionPostSource(
        sourceId,
        client,
        maxDepth = maxDepth,
        maxBlockCount = maxBlockCount,
        collectionTimeout = Duration.ofSeconds(1),
        nanoTime = nanoTime,
    )

    fun nodes(node: BlockNode): List<BlockNode> = listOf(node) + node.children.flatMap(::nodes)

    fun dataViews(node: BlockNode): List<DataViewContent> = nodes(node).mapNotNull { it.content as? DataViewContent }

    fun dataSets(node: BlockNode): List<DataSet> = dataViews(node).map { it.data }

    fun text(cell: List<InlineContent>): String = cell.joinToString("") { (it as InlineContent.Text).text }
}
