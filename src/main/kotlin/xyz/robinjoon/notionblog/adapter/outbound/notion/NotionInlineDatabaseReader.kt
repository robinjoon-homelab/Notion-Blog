package xyz.robinjoon.notionblog.adapter.outbound.notion

import org.slf4j.LoggerFactory
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionBlockMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.net.URISyntaxException

/** Keeps structural membership discovery separate from a saved display view. */
internal class NotionInlineDatabaseReader(
    private val client: NotionApiClient,
    blockMapper: NotionBlockMapper,
    sourceId: SourceId,
    maxDepth: Int,
) {
    private val childrenReader = NotionDatabaseChildrenReader(client, maxDepth)
    private val viewReader = NotionDatabaseViewReader(client, blockMapper, sourceId)

    fun read(
        block: BlockNode,
        sourceDocument: SourceDocumentRef,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): Result {
        val reference = block.content as ReferenceBlockContent.DatabaseLink
        val databaseId = NotionIdNormalizer.normalize(reference.reference.externalId)
        val fallback = normalizeReference(block, reference, databaseId)
        val collection = NotionDatabaseCollection(client, checkDeadline, reserve)
        val database = collection.request { client.fetchDatabase(databaseId) }
        collection.requireMatchingId(database.id, databaseId)
        if (database.inTrash) return Result(fallback, emptyList())
        // Ownership failures must not become display fallbacks that shrink publication membership.
        val children = childrenReader.read(database, sourceDocument, collection)
        val titled = describeReference(fallback, database)
        return Result(readDisplay(titled, databaseId, collection), children)
    }

    data class Result(
        val block: BlockNode?,
        val containedChildren: List<SourceDocumentRef>,
    )

    private fun normalizeReference(
        block: BlockNode,
        reference: ReferenceBlockContent.DatabaseLink,
        databaseId: String,
    ): BlockNode =
        block.copy(
            content =
                reference.copy(
                    reference = reference.reference.copy(externalId = databaseId),
                    originalUrl = URI("https://www.notion.so/$databaseId"),
                ),
            children = emptyList(),
        )

    private fun describeReference(
        block: BlockNode,
        database: NotionDatabaseResponse,
    ): BlockNode {
        val reference = block.content as ReferenceBlockContent.DatabaseLink
        return block.copy(
            content =
                reference.copy(
                    title = database.title.ifBlank { reference.title ?: "Database" },
                    originalUrl = safeUrl(database.url) ?: reference.originalUrl,
                ),
        )
    }

    private fun readDisplay(
        block: BlockNode,
        databaseId: String,
        collection: NotionDatabaseCollection,
    ): BlockNode? =
        try {
            val viewIds = collection.collectIds { cursor -> client.fetchDatabaseViews(databaseId, cursor) }
            val tabs = viewIds.mapNotNull { viewId -> viewReader.read(databaseId, viewId, collection) }
            if (tabs.isEmpty()) {
                null
            } else {
                collection.reserve()
                block.copy(
                    children = listOf(BlockNode(BlockId("database:$databaseId:views"), LayoutBlockContent.TabContainer, children = tabs)),
                )
            }
        } catch (exception: SourceAccessException) {
            logger.debug("Notion database display unavailable: {}", exception.javaClass.simpleName)
            block
        }

    private fun safeUrl(value: String?): URI? {
        if (value == null) return null
        val url =
            try {
                URI(value)
            } catch (exception: URISyntaxException) {
                logger.debug("Notion database link unavailable: {}", exception.javaClass.simpleName)
                return null
            }
        return url.takeIf { it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(NotionInlineDatabaseReader::class.java)
    }
}
