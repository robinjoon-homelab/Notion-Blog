package xyz.robinjoon.notionblog.adapter.output.notion

import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.application.model.StoredPost
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.application.service.ApplyImportedPostService
import xyz.robinjoon.notionblog.application.service.SynchronizationQueryService
import xyz.robinjoon.notionblog.application.service.SynchronizePostService
import xyz.robinjoon.notionblog.application.service.SynchronizePublicationService
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.source.PostSourceBinding
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class NotionFailurePreservationTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val lastSuccessAt = now.minusSeconds(3600)
    private val postId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val reference = SourceDocumentRef(SourceId("notion-main"), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val target = SyncTarget.Post(postId)
    private val lastGoodPost = StoredPost(
        Post(
            postId,
            "Last published title",
            BlockTree(listOf(BlockNode(BlockId("saved-block"), TextBlockContent.Paragraph(listOf(InlineContent.Text("Saved content")))))),
        ),
        SourceRevision("last-good-revision"),
        lastSuccessAt,
    )
    private val lastGoodAvailability = PostAvailability(postId, PostAvailabilityStatus.PUBLISHED, lastSuccessAt)
    private var storedPost = lastGoodPost
    private var availability = lastGoodAvailability
    private var syncState = SyncState(target, lastSuccessAt, now, 1, SyncFailureKind.RETRYABLE_SOURCE)
    private val posts = mockk<PostRepository>()
    private val states = mockk<SyncStateRepository>()
    private val queries = mockk<SynchronizationQueryService>()

    @BeforeEach
    fun startServerAndConfigureStorage() {
        server.start()
        every { queries.loadPost(postId) } returns PostSynchronizationContext(
            PublicationId(UUID.randomUUID()),
            postId,
            reference,
            emptySet(),
        )
        every { posts.findBinding(reference) } returns PostSourceBinding(postId, reference)
        every { posts.find(postId) } answers { storedPost }
        every { posts.saveIdentity(any(), any(), any()) } answers {
            storedPost = storedPost.copy(post = storedPost.post.copy(title = secondArg()))
        }
        every { posts.saveSnapshot(any(), any(), any()) } answers {
            storedPost = StoredPost(firstArg(), secondArg(), thirdArg())
        }
        every { posts.saveAvailability(any()) } answers { availability = firstArg() }
        every { states.find(target) } answers { syncState }
        every { states.save(any()) } answers { syncState = firstArg() }
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["42", "false", "{}", "[]", "\"\"", "\" \""])
    fun `malformed publication metadata records failure and retains published content`(publicUrl: String?) {
        enqueuePage(publicUrl)
        enqueueChildren()

        assertThatThrownBy { service().synchronize(postId) }
            .isInstanceOf(SourceConfigurationException::class.java)

        assertPreservedAfterFailure(SyncFailureKind.CONFIGURATION)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["image", "bookmark", "paragraph"])
    fun `malformed body URLs record mapping failure and retain published content`(blockType: String) {
        enqueuePage("\"https://workspace.notion.site/published-page\"")
        val invalidUrl = "https://example.com/private path"
        val payload = when (blockType) {
            "image" -> """{"type":"external","external":{"url":"$invalidUrl"},"caption":[]}"""
            "bookmark" -> """{"url":"$invalidUrl","caption":[]}"""
            else -> """{"rich_text":[{"type":"text","text":{"content":"Link","link":{"url":"$invalidUrl"}},"annotations":{}}]}"""
        }
        enqueueChildren("""{"id":"body-block","type":"$blockType","has_children":false,"in_trash":false,"$blockType":$payload}""")

        assertThatThrownBy { service().synchronize(postId) }
            .isInstanceOf(SourceMappingException::class.java)
            .hasMessageNotContaining(invalidUrl)

        assertPreservedAfterFailure(SyncFailureKind.MAPPING)
    }

    @Test
    fun `explicit null publication URL still withdraws the post while retaining its snapshot`() {
        enqueuePage("null")
        enqueueChildren()

        service().synchronize(postId)

        assertThat(availability).isEqualTo(PostAvailability(postId, PostAvailabilityStatus.UNPUBLISHED, now))
        assertThat(storedPost.post.content).isEqualTo(lastGoodPost.post.content)
        assertThat(storedPost.sourceRevision).isEqualTo(lastGoodPost.sourceRevision)
        assertThat(syncState).isEqualTo(SyncState(target, now, now.plusSeconds(900), 0, null))
    }

    private fun assertPreservedAfterFailure(kind: SyncFailureKind) {
        assertThat(storedPost).isEqualTo(lastGoodPost)
        assertThat(availability).isEqualTo(lastGoodAvailability)
        assertThat(syncState).isEqualTo(SyncState(target, lastSuccessAt, now.plusSeconds(240), 2, kind))
    }

    private fun service(): SynchronizePostService {
        val source = NotionPostSource(
            sourceId = reference.sourceId,
            client = NotionApiClient(server.url("/v1").toString(), "test-token", Duration.ofSeconds(1), Duration.ofSeconds(5)),
            maxDepth = 8,
            maxBlockCount = 100,
            collectionTimeout = Duration.ofSeconds(5),
        )
        val applyService = ApplyImportedPostService(
            posts,
            states,
            Clock.fixed(now, ZoneOffset.UTC),
            RefreshPolicy(Duration.ofMinutes(15), Duration.ofMinutes(2), Duration.ofMinutes(30)),
        ) { error("existing post must keep its identity") }
        return SynchronizePostService(queries, source, applyService, mockk<SynchronizePublicationService>())
    }

    private fun enqueuePage(publicUrl: String?) {
        val publicationField = publicUrl?.let { "\"public_url\":$it," }.orEmpty()
        enqueueJson(
            """{
                "id":"${reference.externalId}",
                "parent":{"type":"workspace","workspace":true},
                "url":"https://www.notion.so/${reference.externalId}",
                $publicationField
                "in_trash":false,
                "last_edited_time":"2026-10-04T00:00:00Z",
                "properties":{"Name":{"type":"title","title":[{"plain_text":"Incoming title"}]}}
            }""",
        )
    }

    private fun enqueueChildren(block: String = "") {
        enqueueJson("""{"results":[$block],"has_more":false,"next_cursor":null}""")
    }

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.trimIndent()))
    }
}
