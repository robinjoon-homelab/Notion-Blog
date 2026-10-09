package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MeetingNotesStatus
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SynchronizedBlockOrigin
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId

internal class NotionBlockMapper(
    private val sourceId: SourceId,
) {
    private val richText = NotionRichTextMapper(sourceId)
    private val media = NotionMediaMapper(richText)
    private val text = NotionTextBlockMapper(richText, media)
    private val layout = NotionLayoutBlockMapper(richText)

    fun map(
        block: NotionBlockEnvelope,
        children: List<BlockNode> = emptyList(),
        sourceDocument: SourceDocumentRef? = null,
    ): BlockNode {
        if (block.inTrash) {
            throw NotionBlockMappingException("trashed blocks must be excluded before mapping")
        }
        val type =
            block.type.takeIf(String::isNotBlank)
                ?: throw NotionBlockMappingException("block type must not be blank")
        val payload =
            block.payload.takeIf(JsonNode::isObject)
                ?: throw NotionBlockMappingException("$type payload must be an object")

        return try {
            BlockNode(
                id = blockId(block.id),
                content = mapContent(type, payload, block.id, sourceDocument),
                style = NotionStyleMapper.block(payload),
                children = if (type == "tab") layout.normalizeTabChildren(children) else children,
            )
        } catch (exception: NotionBlockMappingException) {
            throw exception
        } catch (exception: IllegalArgumentException) {
            throw NotionBlockMappingException("$type block data is malformed", exception)
        }
    }

    fun mapTabItem(
        block: NotionBlockEnvelope,
        children: List<BlockNode> = emptyList(),
    ): BlockNode {
        if (block.type != "paragraph") {
            throw NotionBlockMappingException("tab item source must be a paragraph")
        }
        val payload =
            block.payload.takeIf(JsonNode::isObject)
                ?: throw NotionBlockMappingException("tab item payload must be an object")
        return BlockNode(
            id = blockId(block.id),
            content = LayoutBlockContent.TabItem(richText.required(payload), media.icon(payload.optionalObject("icon"))),
            style = NotionStyleMapper.block(payload),
            children = children,
        )
    }

    fun mapRichText(values: JsonNode): List<InlineContent> = richText.map(values)

    fun mapIcon(node: JsonNode?): BlockIcon? = media.icon(node)

    fun mapMediaSource(payload: JsonNode): MediaSource = media.source(payload)

    private fun mapContent(
        type: String,
        payload: JsonNode,
        blockExternalId: String,
        sourceDocument: SourceDocumentRef?,
    ): BlockContent =
        text.map(type, payload)
            ?: layout.map(type, payload)
            ?: media.map(type, payload)
            ?: referenceContent(type, payload, blockExternalId)
            ?: when (type) {
                "synced_block" -> syncedBlock(payload, sourceDocument)
                "meeting_notes" -> meetingNotes(payload)
                "unsupported" -> UnsupportedBlockContent(payload.requiredText("block_type"))
                else -> UnsupportedBlockContent(type)
            }

    private fun referenceContent(
        type: String,
        payload: JsonNode,
        blockExternalId: String,
    ): ReferenceBlockContent? =
        when (type) {
            "breadcrumb" -> {
                ReferenceBlockContent.Breadcrumb(emptyList())
            }

            "table_of_contents" -> {
                ReferenceBlockContent.TableOfContents
            }

            "child_page" -> {
                ReferenceBlockContent.ChildPost(payload.requiredText("title"), pageSourceReference(blockExternalId))
            }

            "link_to_page" -> {
                linkToPage(payload)
            }

            "child_database" -> {
                ReferenceBlockContent.DatabaseLink(
                    reference = sourceReference(blockExternalId),
                    originalUrl = null,
                    title = payload.requiredText("title"),
                )
            }

            else -> {
                null
            }
        }

    private fun linkToPage(payload: JsonNode) =
        when (payload.requiredText("type")) {
            "page_id" -> {
                ReferenceBlockContent.DocumentLink(
                    reference = pageSourceReference(payload.requiredText("page_id")),
                    originalUrl = null,
                )
            }

            "database_id" -> {
                ReferenceBlockContent.DatabaseLink(
                    reference = sourceReference(payload.requiredText("database_id")),
                    originalUrl = null,
                )
            }

            else -> {
                throw NotionBlockMappingException("link_to_page type is unsupported")
            }
        }

    private fun syncedBlock(
        payload: JsonNode,
        sourceDocument: SourceDocumentRef?,
    ): ReusableBlockContent.Synchronized {
        val syncedFrom =
            payload.get("synced_from")
                ?: throw NotionBlockMappingException("synced_from is required")
        if (syncedFrom.isNull) {
            return ReusableBlockContent.Synchronized(null)
        }
        if (!syncedFrom.isObject) {
            throw NotionBlockMappingException("synced_from must be an object or null")
        }
        val owner =
            sourceDocument
                ?: throw NotionBlockMappingException("synced block origin requires its containing source document")
        return ReusableBlockContent.Synchronized(
            SynchronizedBlockOrigin(
                document = owner,
                blockExternalId = syncedFrom.requiredText("block_id"),
            ),
        )
    }

    private fun meetingNotes(payload: JsonNode): SpecialBlockContent.MeetingNotes {
        val titleRichText = richText.optional(payload, "title")
        val title = titleRichText.joinToString(separator = "") { inlineLabel(it) }.ifBlank { "Meeting notes" }
        return SpecialBlockContent.MeetingNotes(
            title = title,
            status =
                when (payload.optionalText("status")) {
                    "transcription_not_started" -> MeetingNotesStatus.NOT_STARTED
                    "notes_ready" -> MeetingNotesStatus.COMPLETED
                    "transcription_paused", "transcription_in_progress", "summary_in_progress" -> MeetingNotesStatus.IN_PROGRESS
                    else -> MeetingNotesStatus.OTHER
                },
            summary = emptyList(),
            notesReference = null,
        )
    }

    private fun sourceReference(externalId: String): SourceDocumentRef = SourceDocumentRef(sourceId, externalId)

    private fun pageSourceReference(externalId: String): SourceDocumentRef =
        SourceDocumentRef(sourceId, NotionIdNormalizer.normalize(externalId))

    private fun blockId(value: String): BlockId =
        try {
            BlockId(value)
        } catch (exception: IllegalArgumentException) {
            throw NotionBlockMappingException("block id is invalid", exception)
        }

    private fun inlineLabel(inline: InlineContent): String =
        when (inline) {
            is InlineContent.Text -> inline.text
            is InlineContent.Equation -> inline.expression
            is InlineContent.Mention -> inline.label
        }
}
