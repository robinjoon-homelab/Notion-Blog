package xyz.robinjoon.notionblog.adapter.output.notion

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionBlockMapper
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionBlockMappingException
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionPageMapper
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

internal class NotionPostSource(
    private val sourceId: SourceId,
    private val client: NotionApiClient,
    private val pageMapper: NotionPageMapper = NotionPageMapper(),
    private val blockMapper: NotionBlockMapper = NotionBlockMapper(sourceId),
    private val maxDepth: Int,
    private val maxBlockCount: Int,
    private val collectionTimeout: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) : PostSource {
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
            val state = TraversalState(mutableSetOf(requestedReference.externalId))
            checkDeadline(startedAt)
            val page = client.fetchPage(requestedReference.externalId)
            checkDeadline(startedAt)
            val metadata = pageMapper.map(page, requestedReference)
            val roots = collectChildren(metadata.sourceDocument.externalId, 1, null, metadata.sourceDocument, startedAt, state)
            ImportedPost(
                sourceDocument = metadata.sourceDocument,
                title = metadata.title,
                publicationStatus = metadata.publicationStatus,
                sourceRevision = databaseRevision(metadata.sourceRevision, state.inlineDatabases),
                content = BlockTree(roots),
                containedChildren = state.containedChildren,
            )
        } catch (exception: SourceException) {
            throw exception
        } catch (exception: NotionBlockMappingException) {
            throw SourceMappingException("Notion block could not be mapped", exception)
        } catch (exception: IllegalArgumentException) {
            throw SourceMappingException("Notion post content is malformed", exception)
        }
    }

    private fun collectChildren(
        parentBlockId: String,
        depth: Int,
        parentType: String?,
        sourceDocument: SourceDocumentRef,
        startedAt: Long,
        state: TraversalState,
        skipPreviouslyVisitedBlocks: Boolean = false,
    ): List<BlockNode> {
        if (depth > maxDepth) {
            throw SourceMappingException("Notion block nesting exceeds the configured maximum depth")
        }
        val parentIdentity = blockIdentity(parentBlockId)
        if (!skipPreviouslyVisitedBlocks) {
            state.childrenByParentId[parentIdentity]?.let { children ->
                validateDepth(children, depth)
                return children
            }
        }
        if (!state.collectedChildrenParentIds.add(parentIdentity)) {
            return emptyList()
        }
        checkDeadline(startedAt)
        val blocks = client.fetchDirectBlockChildren(parentBlockId)
        checkDeadline(startedAt)
        if (blocks.isNotEmpty()) {
            state.parentsWithReturnedChildren += parentIdentity
        }
        val activeBlocks = blocks.filterNot(NotionBlockEnvelope::inTrash)
        activeBlocks.mapNotNullTo(state.transcriptBlockIds) { block -> meetingNotesSections(block).transcriptBlockId }
        val collectedChildren = activeBlocks.asSequence()
            .filterNot { block -> blockIdentity(block.id) in state.transcriptBlockIds }
            .filterNot { block -> skipPreviouslyVisitedBlocks && blockIdentity(block.id) in state.visitedBlockIds }
            .mapNotNull { block ->
                countAndVisit(block, state)
                reserveMaterializedBlock(state)
                if (block.type == CHILD_DATABASE_TYPE) {
                    val database = databaseReader.read(
                        blockMapper.map(block, sourceDocument = sourceDocument),
                        sourceDocument,
                        checkDeadline = { checkDeadline(startedAt) },
                        reserve = {
                            checkDeadline(startedAt)
                            reserveMaterializedBlock(state)
                        },
                    )
                    database.containedChildren.forEach { child ->
                        if (!state.visitedPageIds.add(child.externalId)) {
                            throw SourceMappingException("Notion child page collection contains a cycle or duplicate page")
                        }
                        state.containedChildren += child
                    }
                    database.block?.let { displayed ->
                        validateDepth(displayed.children, depth + 1)
                        state.inlineDatabases += displayed
                    }
                    return@mapNotNull database.block
                }
                val ordinaryChildren = if (block.hasChildren && block.type != CHILD_PAGE_TYPE) {
                    collectChildren(
                        block.id,
                        depth + 1,
                        block.type,
                        sourceDocument,
                        startedAt,
                        state,
                        skipPreviouslyVisitedBlocks,
                    )
                } else {
                    emptyList()
                }
                val meetingSections = meetingNotesSections(block)
                val sectionChildren = meetingSections.publicBlockIds
                    .asSequence()
                    .filterNot { sectionBlockId -> blockIdentity(sectionBlockId) in state.transcriptBlockIds }
                    .flatMap { sectionBlockId ->
                        collectChildren(
                            sectionBlockId,
                            depth + 1,
                            block.type,
                            sourceDocument,
                            startedAt,
                            state,
                            skipPreviouslyVisitedBlocks = true,
                        ).asSequence()
                    }
                    .toList()
                val directChildren = ordinaryChildren + sectionChildren
                val children = if (block.hasChildren && directChildren.isEmpty() && blockIdentity(block.id) !in state.parentsWithReturnedChildren) {
                    synchronizedOriginBlockId(block, sourceDocument)
                        ?.let { originBlockId ->
                            copyForSynchronizedReference(
                                referenceBlockId = block.id,
                                children = collectChildren(
                                    originBlockId,
                                    depth + 1,
                                    block.type,
                                    sourceDocument,
                                    startedAt,
                                    state,
                                ),
                                startedAt = startedAt,
                                state = state,
                            )
                        }
                        ?: directChildren
                } else {
                    directChildren
                }
                val mapped = if (parentType == TAB_TYPE && block.type == PARAGRAPH_TYPE) {
                    blockMapper.mapTabItem(block, children)
                } else {
                    blockMapper.map(block, children, sourceDocument)
                }
                if (block.type == CHILD_PAGE_TYPE) {
                    confirmContainedChild(block, sourceDocument, startedAt, state)
                }
                mapped
            }
            .toList()
        state.childrenByParentId[parentIdentity] = collectedChildren
        return collectedChildren
    }

    private fun synchronizedOriginBlockId(
        block: NotionBlockEnvelope,
        sourceDocument: SourceDocumentRef,
    ): String? {
        if (block.type != SYNCED_BLOCK_TYPE) {
            return null
        }
        val content = blockMapper.map(block, sourceDocument = sourceDocument).content
        return (content as ReusableBlockContent.Synchronized).origin?.blockExternalId
    }

    private fun copyForSynchronizedReference(
        referenceBlockId: String,
        children: List<BlockNode>,
        startedAt: Long,
        state: TraversalState,
    ): List<BlockNode> {
        val referenceIdentity = blockIdentity(referenceBlockId)
        return children.map { child -> child.copyForSynchronizedReference(referenceIdentity, startedAt, state) }
    }

    private fun BlockNode.copyForSynchronizedReference(
        referenceIdentity: String,
        startedAt: Long,
        state: TraversalState,
    ): BlockNode {
        reserveMaterializedBlock(state)
        checkDeadline(startedAt)
        if (content is DataViewContent) {
            content.data.columns.forEach {
                checkDeadline(startedAt)
                reserveMaterializedBlock(state)
            }
            content.data.rows.forEach { row ->
                checkDeadline(startedAt)
                reserveMaterializedBlock(state)
                row.cells.forEach {
                    checkDeadline(startedAt)
                    reserveMaterializedBlock(state)
                }
            }
        }
        return copy(
            id = BlockId("synced:$referenceIdentity:${id.value}"),
            children = children.map { child ->
                child.copyForSynchronizedReference(referenceIdentity, startedAt, state)
            },
        )
    }

    private fun validateDepth(
        children: List<BlockNode>,
        depth: Int,
    ) {
        if (children.isEmpty()) {
            return
        }
        if (depth > maxDepth) {
            throw SourceMappingException("Notion block nesting exceeds the configured maximum depth")
        }
        children.forEach { child -> validateDepth(child.children, depth + 1) }
    }

    private fun meetingNotesSections(block: NotionBlockEnvelope): MeetingNotesSections {
        if (block.type != MEETING_NOTES_TYPE) {
            return MeetingNotesSections.EMPTY
        }
        val children = block.payload.get("children") ?: return MeetingNotesSections.EMPTY
        if (children.isNull) {
            return MeetingNotesSections.EMPTY
        }
        if (!children.isObject) {
            throw SourceMappingException("Notion meeting notes children must be an object or null")
        }
        val transcriptBlockId = children.optionalBlockId("transcript_block_id")
        return MeetingNotesSections(
            publicBlockIds = listOfNotNull(
                children.optionalBlockId("summary_block_id"),
                children.optionalBlockId("notes_block_id"),
            ).distinct(),
            transcriptBlockId = transcriptBlockId,
        )
    }

    private fun JsonNode.optionalBlockId(field: String): String? {
        val value = get(field) ?: return null
        if (value.isNull) {
            return null
        }
        if (!value.isString || value.stringValue().isBlank()) {
            throw SourceMappingException("Notion meeting notes $field must be a nonblank block ID or null")
        }
        return try {
            NotionIdNormalizer.normalize(value.stringValue())
        } catch (exception: SourceMappingException) {
            throw SourceMappingException("Notion meeting notes $field is malformed", exception)
        }
    }

    private fun blockIdentity(value: String): String = try {
        NotionIdNormalizer.normalize(value)
    } catch (_: SourceMappingException) {
        value
    }

    private fun countAndVisit(block: NotionBlockEnvelope, state: TraversalState) {
        if (!state.visitedBlockIds.add(blockIdentity(block.id))) {
            throw SourceMappingException("Notion block collection contains a cycle or duplicate block")
        }
        state.blockCount += 1
        if (state.blockCount > maxBlockCount) {
            throw SourceMappingException("Notion block collection exceeds the configured maximum count")
        }
    }

    private fun reserveMaterializedBlock(state: TraversalState) {
        state.materializedBlockCount += 1
        if (state.materializedBlockCount > maxBlockCount) {
            throw SourceMappingException("Notion block collection exceeds the configured maximum count")
        }
    }

    private fun confirmContainedChild(
        block: NotionBlockEnvelope,
        sourceDocument: SourceDocumentRef,
        startedAt: Long,
        state: TraversalState,
    ) {
        val childPageId = NotionIdNormalizer.normalize(block.id)
        if (!state.visitedPageIds.add(childPageId)) {
            throw SourceMappingException("Notion child page collection contains a cycle or duplicate page")
        }
        checkDeadline(startedAt)
        val childPage = client.fetchPage(childPageId)
        checkDeadline(startedAt)
        if (NotionIdNormalizer.normalize(childPage.id) != childPageId) {
            throw SourceMappingException("Notion child page did not match the child page block")
        }
        if (childPage.parent.type == PAGE_PARENT_TYPE) {
            val parentPageId = childPage.parent.pageId
                ?.let(NotionIdNormalizer::normalize)
                ?: throw SourceMappingException("Notion child page parent is malformed")
            if (parentPageId == sourceDocument.externalId) {
                state.containedChildren += SourceDocumentRef(sourceId, childPageId)
            }
        }
    }

    private fun checkDeadline(startedAt: Long) {
        if (nanoTime() - startedAt >= collectionTimeout.toNanos()) {
            throw RetryableSourceException("Notion post collection deadline exceeded")
        }
    }

    private fun databaseRevision(pageRevision: SourceRevision, databases: List<BlockNode>): SourceRevision {
        if (databases.isEmpty()) return pageRevision
        // Row edits and publication changes need not update the parent page's revision.
        val encoded = DATABASE_FINGERPRINT_MAPPER.writeValueAsBytes(databases.map(::databaseFingerprint))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(encoded)
        return SourceRevision("${pageRevision.value}:inline-db-v2:${HexFormat.of().formatHex(fingerprint)}")
    }

    private fun databaseFingerprint(block: BlockNode): List<Any?> = listOf(
        block.id.value,
        block.style.let { listOf(it.foreground?.name, it.background?.name, it.alignment?.name, it.width?.ratio, it.variant?.value) },
        when (val content = block.content) {
            is ReferenceBlockContent.DatabaseLink -> listOf(
                "database",
                content.reference.sourceId.value,
                content.reference.externalId,
                content.originalUrl?.toString(),
                content.title,
            )

            LayoutBlockContent.TabContainer -> listOf("tabs")

            is LayoutBlockContent.TabItem -> listOf("tab", content.title.map(::inlineFingerprint), iconFingerprint(content.icon))

            is DataViewContent -> dataViewFingerprint(content)

            is UnsupportedBlockContent -> listOf("unsupported", content.blockType)

            else -> throw SourceMappingException("Notion inline database contains an unexpected block")
        },
        block.children.map(::databaseFingerprint),
    )

    private fun dataViewFingerprint(content: DataViewContent): List<Any?> = listOf(
        when (content) {
            is DataViewContent.Table -> content.options.let {
                listOf("table", it.wrapCells, it.frozenColumns, it.showVerticalLines)
            }

            is DataViewContent.ListView -> listOf("list")

            is DataViewContent.Gallery -> content.options.let {
                listOf("gallery", it.size.name, it.aspect.name, it.layout.name)
            }
        },
        content.data.title,
        content.data.titleColumnIndex,
        content.data.columns.map { listOf(it.name, it.widthPixels, it.wrap) },
        content.data.rows.map { row ->
            listOf(
                row.cells.map { cell -> cell.map(::inlineFingerprint) },
                linkFingerprint(row.link),
                iconFingerprint(row.icon),
                mediaFingerprint(row.cover),
            )
        },
    )

    private fun inlineFingerprint(inline: InlineContent): List<Any?> = listOf(
        when (inline) {
            is InlineContent.Text -> listOf("text", inline.text, linkFingerprint(inline.link))
            is InlineContent.Equation -> listOf("equation", inline.expression)
            is InlineContent.Mention -> listOf("mention", inline.label, inline.kind.name, linkFingerprint(inline.target))
        },
        inline.annotations.let {
            listOf(it.bold, it.italic, it.strikethrough, it.underline, it.code, it.foreground?.name, it.background?.name)
        },
    )

    private fun linkFingerprint(link: LinkTarget?): List<String?>? = when (link) {
        null -> null
        is LinkTarget.ExternalUrl -> listOf("external", link.url.toString())
        is LinkTarget.SourceDocument -> listOf("document", link.reference.sourceId.value, link.reference.externalId, link.originalUrl?.toString())
    }

    private fun iconFingerprint(icon: BlockIcon?): List<Any?>? = when (icon) {
        null -> null
        is BlockIcon.Emoji -> listOf("emoji", icon.value)
        is BlockIcon.Native -> listOf("native", icon.name, icon.color?.name)
        is BlockIcon.CustomEmoji -> listOf("custom", icon.externalId, icon.name, mediaFingerprint(icon.source))
        is BlockIcon.Media -> listOf("media", mediaFingerprint(icon.source))
    }

    private fun mediaFingerprint(source: MediaSource?): List<String?>? = when (source) {
        null -> null
        is MediaSource.External -> listOf("external", source.url.toString())
        is MediaSource.SourceHosted -> listOf("hosted", source.url.toString(), source.expiresAt?.toString())
    }

    private class TraversalState(
        val visitedPageIds: MutableSet<String>,
        val visitedBlockIds: MutableSet<String> = mutableSetOf(),
        val collectedChildrenParentIds: MutableSet<String> = mutableSetOf(),
        val parentsWithReturnedChildren: MutableSet<String> = mutableSetOf(),
        val childrenByParentId: MutableMap<String, List<BlockNode>> = mutableMapOf(),
        val transcriptBlockIds: MutableSet<String> = mutableSetOf(),
        val containedChildren: MutableList<SourceDocumentRef> = mutableListOf(),
        val inlineDatabases: MutableList<BlockNode> = mutableListOf(),
        var blockCount: Int = 0,
        var materializedBlockCount: Int = 0,
    )

    private data class MeetingNotesSections(
        val publicBlockIds: List<String>,
        val transcriptBlockId: String?,
    ) {
        companion object {
            val EMPTY = MeetingNotesSections(emptyList(), null)
        }
    }

    private companion object {
        val DATABASE_FINGERPRINT_MAPPER = JsonMapper.builder().build()
        const val CHILD_PAGE_TYPE = "child_page"
        const val CHILD_DATABASE_TYPE = "child_database"
        const val MEETING_NOTES_TYPE = "meeting_notes"
        const val PAGE_PARENT_TYPE = "page_id"
        const val PARAGRAPH_TYPE = "paragraph"
        const val SYNCED_BLOCK_TYPE = "synced_block"
        const val TAB_TYPE = "tab"
    }
}
