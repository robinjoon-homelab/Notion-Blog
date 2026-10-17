package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import java.time.Duration

internal object NotionPostSourceFixture {
    const val ROOT = "a0b1c2d3e4f56789abcdef0123456789"
    const val CHILD = "0123456789abcdef0123456789abcdef"
    const val OUTSIDE = "1234567890abcdef1234567890abcdef"

    private val sourceId = SourceId("notion-main")
    private val objectMapper = JsonMapper.builder().build()

    fun source(
        client: NotionApiClient,
        maxDepth: Int = 8,
        maxBlockCount: Int = 100,
    ) = NotionPostSource(
        sourceId = sourceId,
        client = client,
        maxDepth = maxDepth,
        maxBlockCount = maxBlockCount,
        collectionTimeout = Duration.ofSeconds(1),
        nanoTime = { 0L },
    )

    fun timedSource(
        client: NotionApiClient,
        collectionTimeout: Duration,
        nanoTime: () -> Long,
    ) = NotionPostSource(
        sourceId = sourceId,
        client = client,
        maxDepth = 8,
        maxBlockCount = 100,
        collectionTimeout = collectionTimeout,
        nanoTime = nanoTime,
    )

    fun reference(externalId: String) = SourceDocumentRef(sourceId, externalId)

    fun page(
        id: String,
        parent: NotionPageParentResponse = NotionPageParentResponse("workspace", null),
        publicUrl: String? = "https://workspace.notion.site/$id",
    ) = NotionPageResponse(
        id = id,
        parent = parent,
        url = "https://www.notion.so/$id",
        publicUrl = publicUrl,
        inTrash = false,
        lastEditedTime = "2026-08-25T00:00:00Z",
        properties = objectMapper.readTree("""{"Name":{"type":"title","title":[{"plain_text":"$id"}]}}"""),
    )

    fun block(
        id: String,
        type: String,
        hasChildren: Boolean = false,
        payload: String,
    ) = NotionBlockEnvelope(id, type, hasChildren, false, objectMapper.readTree(payload))
}
