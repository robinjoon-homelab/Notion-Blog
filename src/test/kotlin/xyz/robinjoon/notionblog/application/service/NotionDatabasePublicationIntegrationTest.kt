package xyz.robinjoon.notionblog.application.service

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.application.port.input.SynchronizePublicationUseCase
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import javax.sql.DataSource

@SpringBootTest(
    properties = [
        "notion.token=test-token",
        "notion.settings-data-source-id=test-settings",
        "notion.source-id=notion-database-test",
        "blog.synchronization.enabled=false",
    ],
)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotionDatabasePublicationIntegrationTest(
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired dataSource: DataSource,
    @Autowired private val webContext: WebApplicationContext,
) {
    @Autowired
    private lateinit var settings: ApplyImportedSiteConfigurationService

    @Autowired
    private lateinit var synchronize: SynchronizePublicationUseCase

    @Autowired
    private lateinit var posts: PostRepository

    @Autowired
    private lateinit var publications: PublicationRepository

    private val transactions = TransactionTemplate(transactionManager)
    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun resetPublicationAndNotionResponses() {
        jdbc.execute(
            "truncate table site_configuration, publication_member, publication_revision, publication, " +
                "post_availability, post_snapshot, post_source_binding, post, sync_state cascade",
        )
        server.dispatcher = DatabasePublicationDispatcher()
    }

    @Test
    fun `owned database cards open collected blog posts while linked sources stay external and drafts remain private`() {
        val site = configureSite()

        synchronize.synchronize()

        val mvc = MockMvcBuilders.webAppContextSetup(webContext).build()
        val home =
            mvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val publishedLink = linkContaining(home, "Published database post")
        assertThat(publishedLink).startsWith("/posts/")
        assertThat(linkContaining(home, "External reference post"))
            .isEqualTo("https://fixture.notion.site/$EXTERNAL_ROW")
        assertThat(home).doesNotContain("Private database draft")

        val article =
            mvc
                .perform(get(publishedLink))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(article).contains("Body of the published database post")

        val draftId =
            requireNotNull(
                transactions.execute {
                    val rootId = requireNotNull(posts.findBinding(reference(ROOT))).postId
                    val publishedId = requireNotNull(posts.findBinding(reference(PUBLISHED_ROW))).postId
                    val unpublishedId = requireNotNull(posts.findBinding(reference(PRIVATE_ROW))).postId
                    val revision = requireNotNull(publications.findActiveRevision(site.publicationId))
                    assertThat(publications.findMembers(revision.id).map { it.postId })
                        .containsExactlyInAnyOrder(rootId, publishedId, unpublishedId)
                    assertThat(posts.findBinding(reference(EXTERNAL_ROW))).isNull()
                    assertThat(publishedLink).isEqualTo("/posts/${publishedId.value}")
                    unpublishedId
                },
            )
        mvc
            .perform(get("/posts/${draftId.value}"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `owned source access failure preserves the active publication and its rendered article`() {
        val site = configureSite()
        synchronize.synchronize()
        val originalRevision = requireNotNull(transactions.execute { publications.findActiveRevision(site.publicationId) })
        val originalMembers = requireNotNull(transactions.execute { publications.findMembers(originalRevision.id) })
        val mvc = MockMvcBuilders.webAppContextSetup(webContext).build()
        val home =
            mvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val articleLink = linkContaining(home, "Published database post")
        assertThat(articleLink).startsWith("/posts/")
        val originalArticle =
            mvc
                .perform(get(articleLink))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        server.dispatcher = DatabasePublicationDispatcher(denyOwnedQuery = true)

        assertThatThrownBy { synchronize.synchronize() }
            .isInstanceOf(SourceAccessException::class.java)

        transactions.executeWithoutResult {
            assertThat(publications.findActiveRevision(site.publicationId)).isEqualTo(originalRevision)
            assertThat(publications.findMembers(originalRevision.id)).containsExactlyInAnyOrderElementsOf(originalMembers)
        }
        val retainedHome =
            mvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(linkContaining(retainedHome, "Published database post")).isEqualTo(articleLink)
        val retainedArticle =
            mvc
                .perform(get(articleLink))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(retainedArticle).isEqualTo(originalArticle)
        assertThat(retainedArticle).contains("Body of the published database post")
    }

    private fun configureSite() =
        settings
            .apply(
                ImportedSiteConfiguration(
                    rootDocument = reference(ROOT),
                    headerDocument = null,
                    footerDocument = null,
                    metadata = ImportedSiteMetadata("Database blog", null, "en", null),
                    presentationProfileKey = null,
                ),
            ).configuration

    private fun reference(id: String) = SourceDocumentRef(SourceId("notion-database-test"), id)

    private fun linkContaining(
        html: String,
        title: String,
    ): String {
        val links = Regex("""<a\b[^>]*\bhref="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        return requireNotNull(links.findAll(html).singleOrNull { title in it.groupValues[2] }) {
            "Expected one rendered link for $title"
        }.groupValues[1]
    }

    private class DatabasePublicationDispatcher(
        private val denyOwnedQuery: Boolean = false,
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = requireNotNull(request.requestUrl)
            if (denyOwnedQuery && url.encodedPath == "/v1/data_sources/$OWNED_SOURCE/query") {
                return MockResponse().setResponseCode(404)
            }
            val body =
                when (url.encodedPath) {
                    "/v1/pages/$ROOT" -> {
                        page(ROOT, "Blog root", """{"type":"workspace","workspace":true}""")
                    }

                    "/v1/pages/$PUBLISHED_ROW" -> {
                        page(PUBLISHED_ROW, "Published database post", parent("data_source_id", OWNED_SOURCE))
                    }

                    "/v1/pages/$PRIVATE_ROW" -> {
                        page(PRIVATE_ROW, "Private database draft", parent("data_source_id", OWNED_SOURCE), published = false)
                    }

                    "/v1/pages/$EXTERNAL_ROW" -> {
                        page(EXTERNAL_ROW, "External reference post", parent("data_source_id", EXTERNAL_SOURCE))
                    }

                    "/v1/blocks/$ROOT/children" -> {
                        list(
                            """{"object":"block","id":"$DATABASE","type":"child_database","has_children":false,
                        "in_trash":false,"child_database":{"title":"Blog posts"}}""",
                        )
                    }

                    "/v1/blocks/$PUBLISHED_ROW/children" -> {
                        list(paragraph("77777777777777777777777777777777", "Body of the published database post"))
                    }

                    "/v1/blocks/$PRIVATE_ROW/children" -> {
                        list(paragraph("88888888888888888888888888888888", "Private draft body"))
                    }

                    "/v1/databases/$DATABASE" -> {
                        """
                    {"object":"database","id":"$DATABASE","title":[{"plain_text":"Blog posts"}],
                     "url":"https://www.notion.so/$DATABASE","in_trash":false,
                     "parent":${parent("page_id", ROOT)},
                     "data_sources":[{"id":"$OWNED_SOURCE","name":"Owned posts"},{"id":"$EXTERNAL_SOURCE","name":"Linked posts"}]}
                """
                    }

                    "/v1/data_sources/$OWNED_SOURCE" -> {
                        source(OWNED_SOURCE, DATABASE)
                    }

                    "/v1/data_sources/$EXTERNAL_SOURCE" -> {
                        source(EXTERNAL_SOURCE, EXTERNAL_DATABASE)
                    }

                    "/v1/data_sources/$OWNED_SOURCE/query" -> {
                        list(
                            page(PUBLISHED_ROW, "Published database post", parent("data_source_id", OWNED_SOURCE)),
                            page(PRIVATE_ROW, "Private database draft", parent("data_source_id", OWNED_SOURCE), published = false),
                        )
                    }

                    "/v1/views" -> {
                        """
                    {"object":"list","type":"view","view":{},
                     "results":[{"object":"view","id":"$OWNED_VIEW"},{"object":"view","id":"$EXTERNAL_VIEW"}],
                     "has_more":false,"next_cursor":null}
                """
                    }

                    "/v1/views/$OWNED_VIEW" -> {
                        view(OWNED_VIEW, OWNED_SOURCE, "Blog posts")
                    }

                    "/v1/views/$EXTERNAL_VIEW" -> {
                        view(EXTERNAL_VIEW, EXTERNAL_SOURCE, "External references")
                    }

                    "/v1/views/$OWNED_VIEW/queries" -> {
                        query(OWNED_VIEW, PUBLISHED_ROW)
                    }

                    "/v1/views/$EXTERNAL_VIEW/queries" -> {
                        query(EXTERNAL_VIEW, EXTERNAL_ROW)
                    }

                    else -> {
                        return MockResponse().setResponseCode(404)
                    }
                }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(body.trimIndent())
        }

        private fun page(
            id: String,
            title: String,
            parent: String,
            published: Boolean = true,
        ): String {
            val publicUrl = if (published) "\"https://fixture.notion.site/$id\"" else "null"
            return """
                {"object":"page","id":"$id","parent":$parent,"url":"https://www.notion.so/$id",
                 "public_url":$publicUrl,"in_trash":false,"last_edited_time":"2026-10-05T00:00:00Z",
                 "properties":{"Name":{"id":"title","type":"title","title":[
                   {"type":"text","text":{"content":"$title"},"plain_text":"$title","annotations":{}}]}}}
            """
        }

        private fun source(
            id: String,
            databaseId: String,
        ) = """
            {"object":"data_source","id":"$id","parent":${parent("database_id", databaseId)},"in_trash":false,
             "properties":{"Name":{"id":"title","name":"Name","type":"title","title":{}}}}
        """

        private fun view(
            id: String,
            sourceId: String,
            title: String,
        ) = """
            {"object":"view","id":"$id","parent":${parent("database_id", DATABASE)},
             "name":"$title","type":"gallery","data_source_id":"$sourceId",
             "configuration":{"type":"gallery","properties":[{"property_id":"title","visible":true}]}}
        """

        private fun query(
            viewId: String,
            pageId: String,
        ) = """
            {"object":"view_query","id":"$QUERY","view_id":"$viewId",
             "results":[{"object":"page","id":"$pageId"}],"has_more":false,"next_cursor":null}
        """

        private fun paragraph(
            id: String,
            text: String,
        ) = """
            {"object":"block","id":"$id","type":"paragraph","has_children":false,"in_trash":false,
             "paragraph":{"rich_text":[{"type":"text","text":{"content":"$text"},"plain_text":"$text","annotations":{}}]}}
        """

        private fun parent(
            type: String,
            id: String,
        ) = """{"type":"$type","$type":"$id"}"""

        private fun list(vararg values: String) =
            """
            {"object":"list","results":[${values.joinToString(",")}],"has_more":false,"next_cursor":null}
        """
    }

    companion object {
        private const val ROOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DATABASE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val OWNED_SOURCE = "cccccccccccccccccccccccccccccccc"
        private const val EXTERNAL_SOURCE = "dddddddddddddddddddddddddddddddd"
        private const val EXTERNAL_DATABASE = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        private const val OWNED_VIEW = "11111111111111111111111111111111"
        private const val EXTERNAL_VIEW = "22222222222222222222222222222222"
        private const val PUBLISHED_ROW = "33333333333333333333333333333333"
        private const val PRIVATE_ROW = "44444444444444444444444444444444"
        private const val EXTERNAL_ROW = "55555555555555555555555555555555"
        private const val QUERY = "66666666666666666666666666666666"

        private val server = MockWebServer().apply { start() }

        @Container
        @JvmStatic
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun infrastructureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", container::getJdbcUrl)
            registry.add("spring.datasource.username", container::getUsername)
            registry.add("spring.datasource.password", container::getPassword)
            registry.add("notion.base-url") { server.url("/v1").toString().trimEnd('/') }
        }

        @AfterAll
        @JvmStatic
        fun stopNotionServer() {
            server.shutdown()
        }
    }
}
