package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.CONTAINER
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.DATABASE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.HIDDEN_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.PRIVATE_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.QUERY
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.ROOT
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.SECOND_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.SECOND_SOURCE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.SOURCE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.TRASH_ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.VIEW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.dataSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.database
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.list
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.page
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.parent
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.sourceId
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.view
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.viewsList
import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class NotionDatabaseChildrenSourceTest {
    private val fixture = NotionDatabaseChildrenServer()
    private val responses = fixture.responses
    private val queryPages = fixture.queryPages
    private val deniedPaths = fixture.deniedPaths

    @BeforeEach
    fun startServer() {
        fixture.start()
    }

    @AfterEach
    fun stopServer() {
        fixture.stop()
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

        val imported = fixture.source().fetch(reference())

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

        val imported = fixture.source().fetch(reference())

        assertThat(imported.containedChildren).containsExactly(SourceDocumentRef(sourceId, ROW))
        assertThat(imported.content.roots).isEmpty()
    }

    @Test
    fun `discovers owned pages when no display view exists`() {
        responses["/v1/views"] = viewsList()

        assertThat(fixture.source().fetch(reference()).containedChildren).containsExactly(SourceDocumentRef(sourceId, ROW))
    }

    @Test
    fun `keeps structural children when display views are inaccessible`() {
        deniedPaths += "/v1/views"

        assertThat(fixture.source().fetch(reference()).containedChildren).containsExactly(SourceDocumentRef(sourceId, ROW))
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

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceAccessException::class.java)
    }

    @Test
    fun `fails when owned page discovery exceeds the existing work budget`() {
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW), page(PRIVATE_ROW), page(HIDDEN_ROW))

        assertThatThrownBy { fixture.source(maxBlockCount = 3).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
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

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceException::class.java)
    }

    @Test
    fun `fails instead of returning partial children when a later structural page is inaccessible`() {
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW), cursor = "unavailable")

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceAccessException::class.java)
    }

    @Test
    fun `checks the containing post deadline after structural enumeration`() {
        val time = AtomicLong()
        fixture.onRequest = { path -> if (path == "/v1/data_sources/$SOURCE/query") time.set(Duration.ofSeconds(5).toNanos()) }

        assertThatThrownBy { fixture.source(nanoTime = time::get).fetch(reference()) }.isInstanceOf(RetryableSourceException::class.java)
    }

    @Test
    fun `excludes a trashed source`() {
        responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE).replace("\"in_trash\":false", "\"in_trash\":true")

        assertThat(fixture.source().fetch(reference()).containedChildren).isEmpty()
    }

    @Test
    fun `rejects a queried page whose id is the containing page`() {
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROOT))

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `rejects a page repeated by separate owned sources`() {
        responses["/v1/databases/$DATABASE"] = database(sources = listOf(SOURCE, SECOND_SOURCE))
        responses["/v1/data_sources/$SECOND_SOURCE"] = dataSource(SECOND_SOURCE)
        queryPages["/v1/data_sources/$SECOND_SOURCE/query" to null] = list(page(ROW, parent("data_source_id", SECOND_SOURCE)))

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `validates the structural parent even for a trashed query row`() {
        queryPages["/v1/data_sources/$SOURCE/query" to null] = list(page(ROW, parent("data_source_id", SECOND_SOURCE), inTrash = true))

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    private fun nodes(node: BlockNode): List<BlockNode> = listOf(node) + node.children.flatMap(::nodes)
}
