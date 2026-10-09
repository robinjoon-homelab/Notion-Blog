package xyz.robinjoon.notionblog.adapter.outbound.notion.source

import xyz.robinjoon.notionblog.adapter.outbound.notion.NotionDatabaseRevision
import xyz.robinjoon.notionblog.adapter.outbound.notion.NotionInlineDatabaseReader
import xyz.robinjoon.notionblog.adapter.outbound.notion.NotionMeetingNotesSections
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionBlockMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionBlockMappingException
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionPageMapper
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.time.Duration

internal class NotionPostSource(
    private val sourceId: SourceId,
    private val client: NotionApiClient,
    private val maxDepth: Int,
    private val maxBlockCount: Int,
    private val collectionTimeout: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) : PostSource {
    private val pageMapper = NotionPageMapper()
    private val blockMapper = NotionBlockMapper(sourceId)
    private val databaseReader = NotionInlineDatabaseReader(client, blockMapper, sourceId, maxDepth)

    init {
        require(maxDepth > 0) { "Notion maximum block depth must be positive" }
        require(maxBlockCount > 0) { "Notion maximum block count must be positive" }
        require(collectionTimeout.isPositive) { "Notion collection timeout must be positive" }
    }

    override fun fetch(reference: SourceDocumentRef): ImportedPost {
        if (reference.sourceId != sourceId) {
            throw SourceConfigurationException("Notion source reference belongs to another source")
        }
        val startedAt = nanoTime()
        return try {
            val requestedReference = reference.copy(externalId = NotionIdNormalizer.normalize(reference.externalId))
            Collection(requestedReference, startedAt).fetch()
        } catch (exception: NotionBlockMappingException) {
            throw SourceMappingException("Notion block could not be mapped", exception)
        } catch (exception: IllegalArgumentException) {
            throw SourceMappingException("Notion post content is malformed", exception)
        }
    }

    private inner class Collection(
        private val sourceDocument: SourceDocumentRef,
        private val startedAt: Long,
    ) {
        private val visitedPageIds = mutableSetOf(sourceDocument.externalId)
        private val visitedBlockIds = mutableSetOf<String>()
        private val collectedChildrenParentIds = mutableSetOf<String>()
        private val parentsWithReturnedChildren = mutableSetOf<String>()
        private val childrenByParentId = mutableMapOf<String, List<BlockNode>>()
        private val transcriptBlockIds = mutableSetOf<String>()
        private val containedChildren = mutableListOf<SourceDocumentRef>()
        private val inlineDatabases = mutableListOf<BlockNode>()
        private var blockCount = 0
        private var materializedBlockCount = 0

        fun fetch(): ImportedPost {
            checkDeadline()
            val page = client.fetchPage(sourceDocument.externalId)
            checkDeadline()
            val metadata = pageMapper.map(page, sourceDocument)
            val roots = collectChildren(sourceDocument.externalId, 1, null)
            return ImportedPost(
                sourceDocument = metadata.sourceDocument,
                title = metadata.title,
                publicationStatus = metadata.publicationStatus,
                sourceRevision = NotionDatabaseRevision.append(metadata.sourceRevision, inlineDatabases),
                content = BlockTree(roots),
                containedChildren = containedChildren.toList(),
            )
        }

        private fun collectChildren(
            parentBlockId: String,
            depth: Int,
            parentType: String?,
            skipPreviouslyVisitedBlocks: Boolean = false,
        ): List<BlockNode> {
            checkDepth(depth)
            val parentIdentity = blockIdentity(parentBlockId)
            if (!skipPreviouslyVisitedBlocks) {
                childrenByParentId[parentIdentity]?.let { children ->
                    validateDepth(children, depth)
                    return children
                }
            }
            if (!collectedChildrenParentIds.add(parentIdentity)) return emptyList()
            val blocks = fetchActiveChildren(parentBlockId)
            blocks.mapNotNullTo(transcriptBlockIds) { block -> NotionMeetingNotesSections.read(block).transcriptBlockId }
            val children =
                blocks
                    .asSequence()
                    .filterNot { block -> blockIdentity(block.id) in transcriptBlockIds }
                    .filterNot { block -> skipPreviouslyVisitedBlocks && blockIdentity(block.id) in visitedBlockIds }
                    .mapNotNull { block -> collectBlock(block, depth, parentType, skipPreviouslyVisitedBlocks) }
                    .toList()
            childrenByParentId[parentIdentity] = children
            return children
        }

        private fun fetchActiveChildren(parentBlockId: String): List<NotionBlockEnvelope> {
            checkDeadline()
            val blocks = client.fetchDirectBlockChildren(parentBlockId)
            checkDeadline()
            if (blocks.isNotEmpty()) parentsWithReturnedChildren += blockIdentity(parentBlockId)
            return blocks.filterNot(NotionBlockEnvelope::inTrash)
        }

        private fun collectBlock(
            block: NotionBlockEnvelope,
            depth: Int,
            parentType: String?,
            skipPreviouslyVisitedBlocks: Boolean,
        ): BlockNode? {
            countAndVisit(block)
            reserveMaterializedBlock()
            if (block.type == CHILD_DATABASE_TYPE) return collectDatabase(block, depth)
            val children = collectBlockChildren(block, depth, skipPreviouslyVisitedBlocks)
            val mapped =
                if (parentType == TAB_TYPE && block.type == PARAGRAPH_TYPE) {
                    blockMapper.mapTabItem(block, children)
                } else {
                    blockMapper.map(block, children, sourceDocument)
                }
            if (block.type == CHILD_PAGE_TYPE) confirmContainedChild(block)
            return mapped
        }

        private fun collectDatabase(
            block: NotionBlockEnvelope,
            depth: Int,
        ): BlockNode? {
            val database =
                databaseReader.read(
                    blockMapper.map(block, sourceDocument = sourceDocument),
                    sourceDocument,
                    checkDeadline = ::checkDeadline,
                    reserve = ::reserveCollectionItem,
                )
            database.containedChildren.forEach { child ->
                if (!visitedPageIds.add(child.externalId)) {
                    throw SourceMappingException("Notion child page collection contains a cycle or duplicate page")
                }
                containedChildren += child
            }
            database.block?.let { displayed ->
                validateDepth(displayed.children, depth + 1)
                inlineDatabases += displayed
            }
            return database.block
        }

        private fun collectBlockChildren(
            block: NotionBlockEnvelope,
            depth: Int,
            skipPreviouslyVisitedBlocks: Boolean,
        ): List<BlockNode> {
            val ordinaryChildren =
                if (block.hasChildren && block.type != CHILD_PAGE_TYPE) {
                    collectChildren(block.id, depth + 1, block.type, skipPreviouslyVisitedBlocks)
                } else {
                    emptyList()
                }
            val sectionChildren =
                NotionMeetingNotesSections
                    .read(block)
                    .publicBlockIds
                    .asSequence()
                    .filterNot { sectionBlockId -> blockIdentity(sectionBlockId) in transcriptBlockIds }
                    .flatMap { sectionBlockId ->
                        collectChildren(sectionBlockId, depth + 1, block.type, skipPreviouslyVisitedBlocks = true).asSequence()
                    }.toList()
            val directChildren = ordinaryChildren + sectionChildren
            if (!block.hasChildren || directChildren.isNotEmpty() || blockIdentity(block.id) in parentsWithReturnedChildren) {
                return directChildren
            }
            return synchronizedChildren(block, depth) ?: directChildren
        }

        private fun synchronizedChildren(
            block: NotionBlockEnvelope,
            depth: Int,
        ): List<BlockNode>? {
            if (block.type != SYNCED_BLOCK_TYPE) return null
            val content = blockMapper.map(block, sourceDocument = sourceDocument).content as ReusableBlockContent.Synchronized
            val originBlockId = content.origin?.blockExternalId ?: return null
            val children = collectChildren(originBlockId, depth + 1, block.type)
            val referenceIdentity = blockIdentity(block.id)
            return children.map { child -> child.copyForSynchronizedReference(referenceIdentity) }
        }

        private fun BlockNode.copyForSynchronizedReference(referenceIdentity: String): BlockNode {
            reserveMaterializedBlock()
            checkDeadline()
            if (content is DataViewContent) reserveDataView(content)
            return copy(
                id = BlockId("synced:$referenceIdentity:${id.value}"),
                children = children.map { child -> child.copyForSynchronizedReference(referenceIdentity) },
            )
        }

        private fun reserveDataView(content: DataViewContent) {
            content.data.columns.forEach { reserveCollectionItem() }
            content.data.rows.forEach { row ->
                reserveCollectionItem()
                row.cells.forEach { reserveCollectionItem() }
            }
        }

        private fun reserveCollectionItem() {
            checkDeadline()
            reserveMaterializedBlock()
        }

        private fun validateDepth(
            children: List<BlockNode>,
            depth: Int,
        ) {
            if (children.isEmpty()) return
            checkDepth(depth)
            children.forEach { child -> validateDepth(child.children, depth + 1) }
        }

        private fun checkDepth(depth: Int) {
            if (depth > maxDepth) {
                throw SourceMappingException("Notion block nesting exceeds the configured maximum depth")
            }
        }

        private fun countAndVisit(block: NotionBlockEnvelope) {
            if (!visitedBlockIds.add(blockIdentity(block.id))) {
                throw SourceMappingException("Notion block collection contains a cycle or duplicate block")
            }
            blockCount += 1
            if (blockCount > maxBlockCount) {
                throw SourceMappingException("Notion block collection exceeds the configured maximum count")
            }
        }

        private fun reserveMaterializedBlock() {
            materializedBlockCount += 1
            if (materializedBlockCount > maxBlockCount) {
                throw SourceMappingException("Notion block collection exceeds the configured maximum count")
            }
        }

        private fun confirmContainedChild(block: NotionBlockEnvelope) {
            val childPageId = NotionIdNormalizer.normalize(block.id)
            if (!visitedPageIds.add(childPageId)) {
                throw SourceMappingException("Notion child page collection contains a cycle or duplicate page")
            }
            checkDeadline()
            val childPage = client.fetchPage(childPageId)
            checkDeadline()
            if (NotionIdNormalizer.normalize(childPage.id) != childPageId) {
                throw SourceMappingException("Notion child page did not match the child page block")
            }
            if (childPage.parent.type == PAGE_PARENT_TYPE) {
                val parentPageId =
                    childPage.parent.pageId?.let(NotionIdNormalizer::normalize)
                        ?: throw SourceMappingException("Notion child page parent is malformed")
                if (parentPageId == sourceDocument.externalId) {
                    containedChildren += SourceDocumentRef(sourceId, childPageId)
                }
            }
        }

        private fun checkDeadline() {
            if (nanoTime() - startedAt >= collectionTimeout.toNanos()) {
                throw RetryableSourceException("Notion post collection deadline exceeded")
            }
        }
    }

    private fun blockIdentity(value: String): String = NotionIdNormalizer.normalizeOrNull(value) ?: value

    private companion object {
        const val CHILD_PAGE_TYPE = "child_page"
        const val CHILD_DATABASE_TYPE = "child_database"
        const val PAGE_PARENT_TYPE = "page_id"
        const val PARAGRAPH_TYPE = "paragraph"
        const val SYNCED_BLOCK_TYPE = "synced_block"
        const val TAB_TYPE = "tab"
    }
}
