package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionMigrationSource
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import java.time.Duration

class NotionMigrationSourceTest {
    private val server = MockWebServer()
    private val sourceId = SourceId("notion")
    private val selection = MigrationSelection(DATA_SOURCE, MigrationFields("title", "sum", "tag", "date"))

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `inspection discovers every data source and its property IDs from a database URL`() {
        enqueue(database(DATA_SOURCE, OTHER_SOURCE))
        enqueue(schema(DATA_SOURCE, "Posts"))
        enqueue(schema(OTHER_SOURCE, "Books"))

        val inspected = source().inspect("https://app.notion.com/p/Old-Blog-$DATABASE?v=$ROW")

        assertThat(inspected.id).isEqualTo(DATABASE)
        assertThat(inspected.title).isEqualTo("Old Blog")
        assertThat(inspected.dataSources.map { it.name }).containsExactly("Posts", "Books")
        assertThat(
            inspected.dataSources
                .first()
                .properties
                .map { it.id },
        ).containsExactly("title", "sum", "tag", "date")
        assertThat(server.takeRequest().path).isEqualTo("/v1/databases/$DATABASE")
    }

    @Test
    fun `a direct data source ID can be inspected after database lookup returns not found`() {
        enqueue("{}", 404)
        enqueue(schema(DATA_SOURCE, "Posts"))

        val inspected = source().inspect(DATA_SOURCE)

        assertThat(inspected.title).isEqualTo("Posts")
        assertThat(inspected.dataSources.single().id).isEqualTo(DATA_SOURCE)
    }

    @Test
    fun `unsupported hosts or credentials in the supplied URL are rejected`() {
        val invalid =
            listOf(
                "https://notion.so.evil.example/$DATABASE",
                "https://example.com/$DATABASE",
                "http://www.notion.so/$DATABASE",
                "https://user:password@www.notion.so/$DATABASE",
            )

        invalid.forEach { address ->
            assertThatThrownBy { source().inspect(address) }.isInstanceOf(SourceConfigurationException::class.java)
        }
    }

    @Test
    fun `inspection preserves permission failures for the permission guidance`() {
        enqueue("{}", 403)

        assertThatThrownBy { source().inspect("https://www.notion.so/$DATABASE") }
            .isInstanceOf(SourceAuthenticationException::class.java)
    }

    @Test
    fun `a fallback permission failure retains the earlier database access failure`() {
        enqueue("{}", 404)
        enqueue("{}", 403)

        val failure = catchThrowable { source().inspect(DATA_SOURCE) }

        assertThat(failure).isInstanceOf(SourceAuthenticationException::class.java)
        assertThat(requireNotNull(failure).suppressed.single()).isInstanceOf(SourceAccessException::class.java)
    }

    @Test
    fun `catalog returns mapped rows and the next cursor while excluding trash and duplicate rows`() {
        enqueue(schema(DATA_SOURCE, "Posts"))
        enqueue(rows(row(ROW, "A post"), row(ROW, "Duplicate"), row(OTHER_SOURCE, "Trash", true), cursor = "next"))

        val catalog = source().readCatalog(selection)

        assertThat(catalog.entries.map { it.sourceDocument }).containsExactly(SourceDocumentRef(sourceId, ROW))
        assertThat(catalog.entries.single().title).isEqualTo("A post")
        assertThat(catalog.nextCursor).isEqualTo("next")
        server.takeRequest()
        assertThat(server.takeRequest().path).isEqualTo("/v1/data_sources/$DATA_SOURCE/query?filter_properties=title")
    }

    @Test
    fun `catalog rejects a missing or wrongly typed mapping against the source schema`() {
        enqueue(schema(DATA_SOURCE, "Posts").replace("\"type\":\"date\"", "\"type\":\"number\""))

        assertThatThrownBy { source().readCatalog(selection) }.isInstanceOf(SourceConfigurationException::class.java)
    }

    @Test
    fun `catalog rejects a row belonging to another source`() {
        enqueue(schema(DATA_SOURCE, "Posts"))
        enqueue(rows(row(ROW, "Wrong source").replace("\"data_source_id\":\"$DATA_SOURCE\"", "\"data_source_id\":\"$OTHER_SOURCE\"")))

        assertThatThrownBy { source().readCatalog(selection) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `catalog rejects a repeated cursor`() {
        enqueue(schema(DATA_SOURCE, "Posts"))
        enqueue(rows(row(ROW, "A post"), cursor = "same"))

        assertThatThrownBy { source().readCatalog(selection, "same") }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `an empty mapped title does not prevent other catalog entries from being prepared`() {
        enqueue(schema(DATA_SOURCE, "Posts"))
        enqueue(rows(row(ROW, ""), row(OTHER_SOURCE, "A post")))

        val entries = source().readCatalog(selection).entries

        assertThat(entries.map { it.title }).containsExactly("제목 없음", "A post")
    }

    private fun source() =
        NotionMigrationSource(
            sourceId,
            NotionApiClient(server.url("/v1").toString(), "test-token", Duration.ofSeconds(1), Duration.ofSeconds(5)),
        )

    private fun enqueue(
        body: String,
        status: Int = 200,
    ) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun database(vararg sources: String) =
        """{"object":"database","id":"$DATABASE","in_trash":false,"parent":{"type":"workspace","workspace":true},
            "title":[{"plain_text":"Old Blog"}],"data_sources":[${sources.joinToString(",") { """{"id":"$it"}""" }}]}"""

    private fun schema(
        id: String,
        title: String,
    ) = """{"object":"data_source","id":"$id","title":[{"plain_text":"$title"}],"in_trash":false,
            "parent":{"type":"database_id","database_id":"$DATABASE"},"properties":{
            "Name":{"id":"title","name":"Name","type":"title"},"Summary":{"id":"sum","name":"Summary","type":"rich_text"},
            "Tags":{"id":"tag","name":"Tags","type":"multi_select"},"Written":{"id":"date","name":"Written","type":"date"}}}"""

    private fun row(
        id: String,
        title: String,
        trash: Boolean = false,
    ) = """{"object":"page","id":"$id","in_trash":$trash,"last_edited_time":"2026-10-05T00:00:00Z",
            "parent":{"type":"data_source_id","data_source_id":"$DATA_SOURCE"},"url":"https://www.notion.so/$id",
            "properties":{"Name":{"id":"title","type":"title","title":[{"plain_text":"$title"}]}}}"""

    private fun rows(
        vararg rows: String,
        cursor: String? = null,
    ) = """{"object":"list","results":[${rows.joinToString(",")}],"has_more":${cursor != null},
            "next_cursor":${cursor?.let { "\"$it\"" }},"request_status":{"type":"complete"}}"""

    private companion object {
        const val DATABASE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATA_SOURCE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER_SOURCE = "cccccccccccccccccccccccccccccccc"
        const val ROW = "dddddddddddddddddddddddddddddddd"
    }
}
