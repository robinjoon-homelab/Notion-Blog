package xyz.robinjoon.notionblog.application.service

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
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
import org.w3c.dom.NodeList
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.application.model.PostFeedLookupResult
import xyz.robinjoon.notionblog.application.port.input.GetPostFeedUseCase
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.io.ByteArrayInputStream
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

@SpringBootTest(
    properties = [
        "notion.token=test-token",
        "notion.settings-data-source-id=test-settings",
        "blog.public-base-url=https://blog.example",
        "blog.synchronization.enabled=false",
    ],
)
@Import(PostFeedIntegrationTest.FeedTestConfiguration::class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostFeedIntegrationTest(
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired dataSource: DataSource,
    @Autowired private val webContext: WebApplicationContext,
) {
    @Autowired
    private lateinit var importedPosts: ApplyImportedPostService

    @Autowired
    private lateinit var importedSettings: ApplyImportedSiteConfigurationService

    @Autowired
    private lateinit var activatePublication: ActivatePublicationService

    @Autowired
    private lateinit var feedService: GetPostFeedUseCase

    @Autowired
    private lateinit var posts: PostRepository

    @Autowired
    private lateinit var publications: PublicationRepository

    @Autowired
    private lateinit var sites: PausingSiteConfigurationRepository

    private val transactions = TransactionTemplate(transactionManager)
    private val jdbc = JdbcTemplate(dataSource)
    private val mvc by lazy { MockMvcBuilders.webAppContextSetup(webContext).build() }

    @BeforeEach
    fun clearContentAndReadHooks() {
        sites.pauseAfterRead.set(null)
        sites.profilesUnavailable = false
        jdbc.execute(
            "truncate table site_configuration, publication_member, publication_revision, publication, " +
                "post_availability, post_snapshot, post_source_binding, post, sync_state cascade",
        )
    }

    @AfterEach
    fun feedRequestsNeverCallNotion() {
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `feed reads one repeatable snapshot while settings scope and visibility change on another connection`() {
        val oldRoot = published("old-root", "Old root")
        val oldHeader = published("old-header", "Old header")
        val oldFooter = published("old-footer", "Old footer")
        val revoked = published("revoked", "Revoked article")
        val retainedOld = published("old-article", "Old article")
        val newRoot = published("new-root", "New root")
        val newHeader = published("new-header", "New header")
        val newFooter = published("new-footer", "New footer")
        val newArticle = published("new-article", "New article")
        val original = configureSite("old-root", "old-header", "old-footer")
        activateScope(original.publicationId, oldRoot, listOf(oldHeader, oldFooter, revoked, retainedOld))
        val replacement =
            original.copy(
                rootDocument = reference("new-root"),
                headerDocument = reference("new-header"),
                footerDocument = reference("new-footer"),
                metadata = SiteMetadata("Replacement blog", "Replacement description", "ko", null),
            )
        val pause = ReadPause()
        sites.pauseAfterRead.set(pause)
        val executor = Executors.newSingleThreadExecutor()
        val pendingRead = executor.submit<PostFeedLookupResult> { feedService.get() }

        try {
            assertThat(AopUtils.isAopProxy(feedService)).isTrue()
            assertThat(pause.settingsRead.await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(pause.isolation).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
            assertThat(pause.readOnly).isTrue()
            transactions.executeWithoutResult {
                assertThat(connectionBackendId()).isNotEqualTo(pause.backendId)
                sites.save(replacement, now.plusSeconds(1))
                activateScope(original.publicationId, newRoot, listOf(newHeader, newFooter, revoked, newArticle))
                posts.saveAvailability(PostAvailability(revoked, PostAvailabilityStatus.UNPUBLISHED, now.plusSeconds(1)))
            }
            pause.resume.countDown()

            val inFlight = requireNotNull(pendingRead.get(10, TimeUnit.SECONDS)) as PostFeedLookupResult.Found
            assertThat(inFlight.feed.metadata).isEqualTo(original.metadata)
            assertThat(inFlight.feed.entries.map { it.post.id }).containsExactlyInAnyOrder(revoked, retainedOld)

            val fresh = feedService.get() as PostFeedLookupResult.Found
            assertThat(fresh.feed.metadata).isEqualTo(replacement.metadata)
            assertThat(fresh.feed.entries.map { it.post.id }).containsExactly(newArticle)
        } finally {
            pause.resume.countDown()
            pendingRead.cancel(true)
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `unpublished root and unavailable presentation profile do not hide a published descendant from RSS`() {
        val fixture = seedFeed()
        transactions.executeWithoutResult {
            posts.saveAvailability(PostAvailability(fixture.rootId, PostAvailabilityStatus.UNPUBLISHED, now))
        }
        sites.profilesUnavailable = true

        val response = requestFeed()

        assertThat(itemTitles(response)).containsExactly("Published article")
        mvc
            .perform(get("/posts/${fixture.articleId.value}"))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `root and article HTML expose the configured feed without a footer document`() {
        val fixture = seedFeed()

        listOf("/", "/posts/${fixture.articleId.value}").forEach { path ->
            val html =
                mvc
                    .perform(get(path))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            val alternate = Regex("""<link\b[^>]*rel="alternate"[^>]*>""").findAll(html).toList()
            assertThat(alternate).hasSize(1)
            assertThat(alternate.single().value)
                .contains("type=\"application/rss+xml\"", "href=\"https://blog.example/feed.xml\"")
            assertThat(html).containsPattern("""<a\b[^>]*href="https://blog\.example/feed\.xml"[^>]*>\s*RSS 구독\s*</a>""")
        }
    }

    @Test
    fun `committed unpublishing invalidates the old validator and serves an empty feed`() {
        val fixture = seedFeed()
        val before = requestFeed()
        val oldEtag = etag(before)
        assertThat(itemTitles(before)).containsExactly("Published article")
        importedPosts.apply(fixture.article.copy(publicationStatus = ImportedPublicationStatus.UNPUBLISHED))

        val after = requestFeed(oldEtag)

        assertThat(itemTitles(after)).isEmpty()
        assertThat(etag(after)).isNotEqualTo(oldEtag)
        val unchanged =
            mvc
                .perform(get("/feed.xml").header("If-None-Match", etag(after)))
                .andExpect(status().isNotModified)
                .andReturn()
                .response
        assertThat(unchanged.contentAsByteArray).isEmpty()
        assertThat(unchanged.getHeader("ETag")).isEqualTo(etag(after))
        assertThat(unchanged.getHeader("Cache-Control")).isEqualTo("no-cache")
    }

    @Test
    fun `committed scope removal invalidates the old validator and removes the last item`() {
        val fixture = seedFeed()
        val oldEtag = etag(requestFeed())

        activateScope(fixture.configuration.publicationId, fixture.rootId, emptyList())

        val response = requestFeed(oldEtag)
        assertThat(itemTitles(response)).isEmpty()
        assertThat(etag(response)).isNotEqualTo(oldEtag)
    }

    @Test
    fun `channel metadata and displayed article edits each invalidate the old validator`() {
        val fixture = seedFeed()
        val initialEtag = etag(requestFeed())
        transactions.executeWithoutResult {
            sites.save(
                fixture.configuration.copy(metadata = SiteMetadata("Renamed blog", "New description", "ko", null)),
                now.plusSeconds(1),
            )
        }

        val metadataChanged = requestFeed(initialEtag)
        assertThat(etag(metadataChanged)).isNotEqualTo(initialEtag)
        assertThat(xmlValues(metadataChanged, "/rss/channel/title")).containsExactly("Renamed blog")
        assertThat(xmlValues(metadataChanged, "/rss/channel/description")).containsExactly("New description")
        assertThat(xmlValues(metadataChanged, "/rss/channel/language")).containsExactly("ko")
        importedPosts.apply(
            fixture.article.copy(
                title = "Revised article",
                sourceRevision = SourceRevision("revision-2"),
                content = paragraph("Updated visible summary"),
            ),
        )

        val articleChanged = requestFeed(etag(metadataChanged))
        assertThat(etag(articleChanged)).isNotEqualTo(etag(metadataChanged))
        assertThat(itemTitles(articleChanged)).containsExactly("Revised article")
        assertThat(xmlValues(articleChanged, "/rss/channel/item/description")).containsExactly("Updated visible summary")
        assertThat(xmlValues(articleChanged, "/rss/channel/item/guid"))
            .isEqualTo(xmlValues(metadataChanged, "/rss/channel/item/guid"))
        assertThat(xmlValues(articleChanged, "/rss/channel/item/pubDate"))
            .isEqualTo(xmlValues(metadataChanged, "/rss/channel/item/pubDate"))
    }

    @Test
    fun `corrupt selected snapshot returns unavailable even with old or wildcard validators`() {
        val fixture = seedFeed()
        val oldEtag = etag(requestFeed())
        jdbc.update("update post_snapshot set snapshot_json = '{}'::jsonb where post_id = ?", fixture.articleId.value)

        assertUnavailableFor(oldEtag)
    }

    @Test
    fun `missing settings or active publication returns unavailable before conditional request evaluation`() {
        val fixture = seedFeed()
        val oldEtag = etag(requestFeed())
        jdbc.update("delete from site_configuration")
        assertUnavailableFor(oldEtag)

        transactions.executeWithoutResult { sites.save(fixture.configuration, now) }
        jdbc.update("update publication set root_post_id = null, active_revision_id = null")
        assertUnavailableFor(oldEtag)
    }

    @Test
    fun `active pointer referencing a superseded revision is unavailable rather than an empty feed`() {
        seedFeed()
        val oldEtag = etag(requestFeed())
        jdbc.update("update publication_revision set state = 'SUPERSEDED' where state = 'ACTIVE'")

        assertUnavailableFor(oldEtag)
    }

    private fun seedFeed(): FeedFixture {
        val rootId = published("root", "Blog root")
        val article = imported("article", "Published article", "Visible summary")
        val articleId = importedPosts.apply(article)
        val configuration = configureSite("root")
        activateScope(configuration.publicationId, rootId, listOf(articleId))
        return FeedFixture(configuration, rootId, articleId, article)
    }

    private fun published(
        externalId: String,
        title: String,
    ): PostId = importedPosts.apply(imported(externalId, title, title))

    private fun imported(
        externalId: String,
        title: String,
        summary: String,
    ) = ImportedPost(
        sourceDocument = reference(externalId),
        title = title,
        publicationStatus = ImportedPublicationStatus.PUBLISHED,
        sourceRevision = SourceRevision("revision-1"),
        content = paragraph(summary),
        containedChildren = emptyList(),
    )

    private fun paragraph(text: String) =
        BlockTree(
            listOf(BlockNode(BlockId("paragraph"), TextBlockContent.Paragraph(listOf(InlineContent.Text(text))))),
        )

    private fun configureSite(
        root: String,
        header: String? = null,
        footer: String? = null,
    ): SiteConfiguration =
        importedSettings
            .apply(
                ImportedSiteConfiguration(
                    rootDocument = reference(root),
                    headerDocument = header?.let(::reference),
                    footerDocument = footer?.let(::reference),
                    metadata = ImportedSiteMetadata("Feed blog", "Feed description", "en", null),
                    presentationProfileKey = null,
                ),
            ).configuration

    private fun activateScope(
        publicationId: PublicationId,
        root: PostId,
        children: List<PostId>,
    ) {
        transactions.executeWithoutResult {
            val revision = PublicationRevision(PublicationRevisionId(UUID.randomUUID()), publicationId, PublicationRevisionState.STAGING)
            publications.createRevision(revision, now)
            publications.saveMembers(
                revision.id,
                listOf(PublicationMember(revision.id, root, null, 0)) + children.map { PublicationMember(revision.id, it, root, 1) },
            )
            activatePublication.activate(revision.id)
        }
    }

    private fun requestFeed(validator: String? = null): MockHttpServletResponse {
        val request = get("/feed.xml")
        validator?.let { request.header("If-None-Match", it) }
        val response =
            mvc
                .perform(request)
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache")
        assertThat(response.contentType).startsWith("application/rss+xml")
        return response
    }

    private fun assertUnavailableFor(oldEtag: String) {
        listOf(oldEtag, "*").forEach { validator ->
            val response =
                mvc
                    .perform(get("/feed.xml").header("If-None-Match", validator))
                    .andExpect(status().isServiceUnavailable)
                    .andReturn()
                    .response
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
            assertThat(response.getHeader("ETag")).isNull()
        }
    }

    private fun etag(response: MockHttpServletResponse): String = requireNotNull(response.getHeader("ETag"))

    private fun itemTitles(response: MockHttpServletResponse): List<String> = xmlValues(response, "/rss/channel/item/title")

    private fun xmlValues(
        response: MockHttpServletResponse,
        expression: String,
    ): List<String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(response.contentAsByteArray))
        val nodes = requireNotNull(XPathFactory.newInstance().newXPath().evaluate(expression, document, XPathConstants.NODESET)) as NodeList
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    private fun reference(externalId: String) = SourceDocumentRef(SourceId("feed-test"), externalId)

    private data class FeedFixture(
        val configuration: SiteConfiguration,
        val rootId: PostId,
        val articleId: PostId,
        val article: ImportedPost,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class FeedTestConfiguration {
        @Bean
        @Primary
        fun feedClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)

        @Bean
        @Primary
        fun pausingSiteConfigurationRepository(
            @Qualifier("siteConfigurationRepository") delegate: SiteConfigurationRepository,
        ): PausingSiteConfigurationRepository = PausingSiteConfigurationRepository(delegate)
    }

    class PausingSiteConfigurationRepository(
        private val delegate: SiteConfigurationRepository,
    ) : SiteConfigurationRepository by delegate {
        val pauseAfterRead = AtomicReference<ReadPause?>()
        var profilesUnavailable = false

        override fun findCurrent(): SiteConfiguration? {
            val configuration = delegate.findCurrent()
            pauseAfterRead.getAndSet(null)?.let { pause ->
                val connection = TransactionManager.current().connection.connection as Connection
                pause.isolation = connection.transactionIsolation
                pause.readOnly = connection.isReadOnly
                pause.backendId = connectionBackendId()
                pause.settingsRead.countDown()
                check(pause.resume.await(10, TimeUnit.SECONDS)) { "timed out waiting for concurrent feed changes" }
            }
            return configuration
        }

        override fun findProfile(reference: PresentationProfileRef): PresentationProfile? =
            if (profilesUnavailable) null else delegate.findProfile(reference)
    }

    class ReadPause {
        val settingsRead = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var isolation: Int? = null
        var readOnly: Boolean? = null
        var backendId: Int? = null
    }

    companion object {
        private val now = Instant.parse("2026-10-05T03:04:05Z")
        private val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(404)
                    }
                start()
            }

        private fun connectionBackendId(): Int =
            requireNotNull(
                TransactionManager.current().exec("select pg_backend_pid()") { result ->
                    check(result.next())
                    result.getInt(1)
                },
            )

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
