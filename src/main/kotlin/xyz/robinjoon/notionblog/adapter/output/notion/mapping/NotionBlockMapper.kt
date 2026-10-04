package xyz.robinjoon.notionblog.adapter.output.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.domain.post.block.content.MeetingNotesStatus
import xyz.robinjoon.notionblog.domain.post.block.content.NumberedListFormat
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.inline.MentionKind
import xyz.robinjoon.notionblog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.post.block.style.BlockStyle
import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken
import xyz.robinjoon.notionblog.domain.post.block.style.WidthToken
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

internal class NotionBlockMapper(
    private val sourceId: SourceId,
) {
    fun map(
        block: NotionBlockEnvelope,
        children: List<BlockNode> = emptyList(),
        sourceDocument: SourceDocumentRef? = null,
    ): BlockNode {
        if (block.inTrash) {
            throw NotionBlockMappingException("trashed blocks must be excluded before mapping")
        }
        val type = block.type.takeIf(String::isNotBlank)
            ?: throw NotionBlockMappingException("block type must not be blank")
        val payload = block.payload.takeIf(JsonNode::isObject)
            ?: throw NotionBlockMappingException("$type payload must be an object")

        return try {
            BlockNode(
                id = blockId(block.id),
                content = mapContent(type, payload, block.id, sourceDocument),
                style = style(payload),
                children = if (type == "tab") normalizeTabChildren(children) else children,
            )
        } catch (exception: NotionBlockMappingException) {
            throw exception
        } catch (exception: IllegalArgumentException) {
            throw NotionBlockMappingException("$type block data is malformed", exception)
        }
    }

    fun mapTabItem(block: NotionBlockEnvelope, children: List<BlockNode> = emptyList()): BlockNode {
        if (block.type != "paragraph") {
            throw NotionBlockMappingException("tab item source must be a paragraph")
        }
        val payload = block.payload.takeIf(JsonNode::isObject)
            ?: throw NotionBlockMappingException("tab item payload must be an object")
        return BlockNode(
            id = blockId(block.id),
            content = LayoutBlockContent.TabItem(richText(payload), icon(payload.optionalObject("icon"))),
            style = style(payload),
            children = children,
        )
    }

    fun mapRichText(values: JsonNode): List<InlineContent> {
        if (!values.isArray) throw NotionBlockMappingException("rich text must be an array")
        return values.toList().map(::inline)
    }

    fun mapIcon(node: JsonNode?): BlockIcon? = icon(node)

    fun mapMediaSource(payload: JsonNode): MediaSource = when (payload.requiredText("type")) {
        "external" -> MediaSource.External(payload.requiredObject("external").safeUri("url"))

        "file" -> {
            val file = payload.requiredObject("file")
            val expiresAt = file.optionalText("expiry_time")?.let(::parseInstant)
            MediaSource.SourceHosted(file.safeUri("url"), expiresAt)
        }

        else -> throw NotionBlockMappingException("media source is unsupported")
    }

    private fun mapContent(
        type: String,
        payload: JsonNode,
        blockExternalId: String,
        sourceDocument: SourceDocumentRef?,
    ) = when (type) {
        "paragraph" -> TextBlockContent.Paragraph(richText(payload))

        "heading_1" -> heading(payload, xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel.ONE)

        "heading_2" -> heading(payload, xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel.TWO)

        "heading_3" -> heading(payload, xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel.THREE)

        "heading_4" -> heading(payload, xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel.FOUR)

        "bulleted_list_item" -> ListBlockContent.BulletedItem(richText(payload))

        "numbered_list_item" -> numberedListItem(payload)

        "to_do" -> ListBlockContent.ToDoItem(richText(payload), payload.requiredBoolean("checked"))

        "toggle" -> TextBlockContent.Toggle(richText(payload))

        "quote" -> TextBlockContent.Quote(richText(payload))

        "callout" -> TextBlockContent.Callout(richText(payload), icon(payload.optionalObject("icon")))

        "code" -> TextBlockContent.Code(
            richText = richText(payload),
            language = payload.requiredText("language"),
            caption = richTextOrEmpty(payload, "caption"),
        )

        "equation" -> TextBlockContent.Equation(payload.requiredText("expression"))

        "divider" -> LayoutBlockContent.Divider

        "column_list" -> LayoutBlockContent.ColumnList

        "column" -> LayoutBlockContent.Column(payload.optionalDouble("width_ratio")?.let(::WidthToken))

        "tab" -> LayoutBlockContent.TabContainer

        "table" -> LayoutBlockContent.Table(
            width = payload.requiredPositiveInt("table_width"),
            hasColumnHeader = payload.requiredBoolean("has_column_header"),
            hasRowHeader = payload.requiredBoolean("has_row_header"),
        )

        "table_row" -> LayoutBlockContent.TableRow(
            payload.requiredArray("cells").map { cell ->
                if (!cell.isArray) throw NotionBlockMappingException("table row cells must be arrays")
                cell.toList().map(::inline)
            },
        )

        "breadcrumb" -> ReferenceBlockContent.Breadcrumb(emptyList())

        "table_of_contents" -> ReferenceBlockContent.TableOfContents

        "child_page" -> ReferenceBlockContent.ChildPost(payload.requiredText("title"), pageSourceReference(blockExternalId))

        "link_to_page" -> linkToPage(payload)

        "child_database" -> {
            ReferenceBlockContent.DatabaseLink(
                reference = sourceReference(blockExternalId),
                originalUrl = null,
                title = payload.requiredText("title"),
            )
        }

        "synced_block" -> syncedBlock(payload, sourceDocument)

        "template" -> ReusableBlockContent.Template(richText(payload))

        "bookmark" -> MediaBlockContent.Bookmark(payload.safeUri("url"), richTextOrEmpty(payload, "caption"))

        "image" -> media(payload, MediaType.IMAGE)

        "video" -> media(payload, MediaType.VIDEO)

        "audio" -> media(payload, MediaType.AUDIO)

        "pdf" -> media(payload, MediaType.PDF)

        "file" -> media(payload, MediaType.FILE)

        "embed" -> MediaBlockContent.Embed(payload.safeUri("url"), richTextOrEmpty(payload, "caption"))

        "link_preview" -> MediaBlockContent.LinkPreview(payload.safeUri("url"))

        "meeting_notes" -> meetingNotes(payload)

        "unsupported" -> UnsupportedBlockContent(payload.requiredText("block_type"))

        else -> UnsupportedBlockContent(type)
    }

    private fun heading(
        payload: JsonNode,
        level: xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel,
    ) = TextBlockContent.Heading(
        level = level,
        richText = richText(payload),
        isToggleable = payload.requiredBoolean("is_toggleable"),
    )

    private fun numberedListItem(payload: JsonNode): ListBlockContent.NumberedItem {
        val hasExplicitStart = payload.has("list_start_index")
        val hasExplicitFormat = payload.has("list_format")
        return ListBlockContent.NumberedItem(
            richText = richText(payload),
            startNumber = if (hasExplicitStart) payload.requiredPositiveInt("list_start_index") else 1,
            displayFormat = if (hasExplicitFormat) numberedListFormat(payload.requiredText("list_format")) else NumberedListFormat.DECIMAL,
            startsNewList = hasExplicitStart || hasExplicitFormat,
        )
    }

    private fun numberedListFormat(value: String): NumberedListFormat = when (value) {
        "numbers" -> NumberedListFormat.DECIMAL
        "letters" -> NumberedListFormat.LOWER_ALPHA
        "roman" -> NumberedListFormat.LOWER_ROMAN
        else -> throw NotionBlockMappingException("numbered list format is unsupported")
    }

    private fun linkToPage(payload: JsonNode) = when (payload.requiredText("type")) {
        "page_id" -> ReferenceBlockContent.DocumentLink(
            reference = pageSourceReference(payload.requiredText("page_id")),
            originalUrl = null,
        )

        "database_id" -> ReferenceBlockContent.DatabaseLink(
            reference = sourceReference(payload.requiredText("database_id")),
            originalUrl = null,
        )

        else -> throw NotionBlockMappingException("link_to_page type is unsupported")
    }

    private fun syncedBlock(
        payload: JsonNode,
        sourceDocument: SourceDocumentRef?,
    ): ReusableBlockContent.Synchronized {
        val syncedFrom = payload.get("synced_from")
            ?: throw NotionBlockMappingException("synced_from is required")
        if (syncedFrom.isNull) {
            return ReusableBlockContent.Synchronized(null)
        }
        if (!syncedFrom.isObject) {
            throw NotionBlockMappingException("synced_from must be an object or null")
        }
        val owner = sourceDocument
            ?: throw NotionBlockMappingException("synced block origin requires its containing source document")
        return ReusableBlockContent.Synchronized(
            xyz.robinjoon.notionblog.domain.post.block.content.SynchronizedBlockOrigin(
                document = owner,
                blockExternalId = syncedFrom.requiredText("block_id"),
            ),
        )
    }

    private fun media(payload: JsonNode, mediaType: MediaType): MediaBlockContent.Media = MediaBlockContent.Media(
        mediaType = mediaType,
        source = mapMediaSource(payload),
        fileName = payload.optionalText("name"),
        caption = richTextOrEmpty(payload, "caption"),
    )

    private fun meetingNotes(payload: JsonNode): SpecialBlockContent.MeetingNotes {
        val titleRichText = richTextOrEmpty(payload, "title")
        val title = titleRichText.joinToString(separator = "") { inlineLabel(it) }.ifBlank { "Meeting notes" }
        return SpecialBlockContent.MeetingNotes(
            title = title,
            status = when (payload.optionalText("status")) {
                "transcription_not_started" -> MeetingNotesStatus.NOT_STARTED
                "notes_ready" -> MeetingNotesStatus.COMPLETED
                "transcription_paused", "transcription_in_progress", "summary_in_progress" -> MeetingNotesStatus.IN_PROGRESS
                else -> MeetingNotesStatus.OTHER
            },
            summary = emptyList(),
            notesReference = null,
        )
    }

    private fun normalizeTabChildren(children: List<BlockNode>): List<BlockNode> = children.map { child ->
        when (val content = child.content) {
            is TextBlockContent.Paragraph -> child.copy(
                content = LayoutBlockContent.TabItem(title = content.richText, icon = null),
            )

            is LayoutBlockContent.TabItem -> child

            else -> throw NotionBlockMappingException("tab children must be paragraphs or normalized tab items")
        }
    }

    private fun icon(node: JsonNode?): BlockIcon? {
        if (node == null) return null
        node.optionalText("emoji")?.let { return BlockIcon.Emoji(it) }
        node.optionalObject("icon")?.let { nativeIcon ->
            return BlockIcon.Native(
                name = nativeIcon.requiredText("name"),
                color = nativeIconColor(nativeIcon.requiredText("color")),
            )
        }
        node.optionalObject("custom_emoji")?.let { customEmoji ->
            return BlockIcon.CustomEmoji(
                externalId = customEmoji.requiredText("id"),
                name = customEmoji.requiredText("name"),
                source = MediaSource.External(customEmoji.safeUri("url")),
            )
        }
        val file = node.optionalObject("file") ?: node.optionalObject("external")
        return file?.let { mediaNode ->
            val source = if (node.has("file")) {
                MediaSource.SourceHosted(mediaNode.safeUri("url"), mediaNode.optionalText("expiry_time")?.let(::parseInstant))
            } else {
                MediaSource.External(mediaNode.safeUri("url"))
            }
            BlockIcon.Media(source)
        }
    }

    private fun nativeIconColor(value: String): ColorToken? {
        val split = color(value)
        if (split?.background != null) {
            throw NotionBlockMappingException("native icon color must be a foreground color")
        }
        return split?.foreground
    }

    private fun richText(payload: JsonNode, field: String = "rich_text"): List<InlineContent> = payload.requiredArray(field).map(::inline)

    private fun richTextOrEmpty(payload: JsonNode, field: String): List<InlineContent> = payload.optionalArray(field)?.map(::inline).orEmpty()

    private fun inline(node: JsonNode): InlineContent {
        val annotations = annotations(node.requiredObject("annotations"))
        return when (node.requiredText("type")) {
            "text" -> {
                val text = node.requiredObject("text")
                InlineContent.Text(
                    text = text.get("content")?.takeIf(JsonNode::isString)?.stringValue()
                        ?: throw NotionBlockMappingException("text content must be a string"),
                    annotations = annotations,
                    link = text.optionalObject("link")?.safeUri("url")?.let(LinkTarget::ExternalUrl),
                )
            }

            "equation" -> InlineContent.Equation(
                expression = node.requiredObject("equation").requiredText("expression"),
                annotations = annotations,
            )

            "mention" -> mention(node, annotations)

            else -> throw NotionBlockMappingException("rich text type is unsupported")
        }
    }

    private fun mention(node: JsonNode, annotations: TextAnnotations): InlineContent.Mention {
        val mention = node.requiredObject("mention")
        val type = mention.requiredText("type")
        val href = node.optionalText("href")?.let(::parseSafeUri)
        val target = when (type) {
            "page" -> LinkTarget.SourceDocument(
                reference = pageSourceReference(mention.requiredObject("page").requiredText("id")),
                originalUrl = href,
            )

            "link_preview" -> href?.let(LinkTarget::ExternalUrl)
                ?: mention.requiredObject("link_preview").safeUri("url").let(LinkTarget::ExternalUrl)

            else -> href?.let(LinkTarget::ExternalUrl)
        }
        return InlineContent.Mention(
            label = node.requiredText("plain_text"),
            kind = when (type) {
                "page" -> MentionKind.DOCUMENT
                "database" -> MentionKind.DATABASE
                "date" -> MentionKind.DATE
                "template_mention" -> MentionKind.TEMPLATE
                "link_preview" -> MentionKind.LINK_PREVIEW
                "user" -> MentionKind.USER
                else -> MentionKind.OTHER
            },
            annotations = annotations,
            target = target,
        )
    }

    private fun annotations(node: JsonNode): TextAnnotations = TextAnnotations(
        bold = node.optionalBoolean("bold") ?: false,
        italic = node.optionalBoolean("italic") ?: false,
        strikethrough = node.optionalBoolean("strikethrough") ?: false,
        underline = node.optionalBoolean("underline") ?: false,
        code = node.optionalBoolean("code") ?: false,
        foreground = color(node.optionalText("color"))?.foreground,
        background = color(node.optionalText("color"))?.background,
    )

    private fun style(payload: JsonNode): BlockStyle {
        val color = color(payload.optionalText("color"))
        return BlockStyle(foreground = color?.foreground, background = color?.background)
    }

    private fun color(value: String?): SplitColor? = when (value) {
        null, "default" -> null
        "gray" -> SplitColor(foreground = ColorToken.GRAY)
        "brown" -> SplitColor(foreground = ColorToken.BROWN)
        "orange" -> SplitColor(foreground = ColorToken.ORANGE)
        "yellow" -> SplitColor(foreground = ColorToken.YELLOW)
        "green" -> SplitColor(foreground = ColorToken.GREEN)
        "blue" -> SplitColor(foreground = ColorToken.BLUE)
        "purple" -> SplitColor(foreground = ColorToken.PURPLE)
        "pink" -> SplitColor(foreground = ColorToken.PINK)
        "red" -> SplitColor(foreground = ColorToken.RED)
        "gray_background" -> SplitColor(background = ColorToken.GRAY)
        "brown_background" -> SplitColor(background = ColorToken.BROWN)
        "orange_background" -> SplitColor(background = ColorToken.ORANGE)
        "yellow_background" -> SplitColor(background = ColorToken.YELLOW)
        "green_background" -> SplitColor(background = ColorToken.GREEN)
        "blue_background" -> SplitColor(background = ColorToken.BLUE)
        "purple_background" -> SplitColor(background = ColorToken.PURPLE)
        "pink_background" -> SplitColor(background = ColorToken.PINK)
        "red_background" -> SplitColor(background = ColorToken.RED)
        else -> throw NotionBlockMappingException("color is unsupported")
    }

    private fun sourceReference(externalId: String): SourceDocumentRef = SourceDocumentRef(sourceId, externalId)

    private fun pageSourceReference(externalId: String): SourceDocumentRef = SourceDocumentRef(sourceId, NotionIdNormalizer.normalize(externalId))

    private fun blockId(value: String): BlockId = try {
        BlockId(value)
    } catch (exception: IllegalArgumentException) {
        throw NotionBlockMappingException("block id is invalid", exception)
    }

    private fun JsonNode.safeUri(field: String): URI = parseSafeUri(requiredText(field))

    private fun parseSafeUri(value: String): URI {
        val uri = try {
            URI(value)
        } catch (exception: URISyntaxException) {
            throw NotionBlockMappingException("URL is invalid", exception)
        }
        if (!uri.isAbsolute || uri.scheme.lowercase(Locale.ROOT) !in setOf("http", "https")) {
            throw NotionBlockMappingException("URL must use http or https")
        }
        return uri
    }

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value)
    } catch (exception: DateTimeParseException) {
        throw NotionBlockMappingException("media expiry is invalid", exception)
    }

    private fun inlineLabel(inline: InlineContent): String = when (inline) {
        is InlineContent.Text -> inline.text
        is InlineContent.Equation -> inline.expression
        is InlineContent.Mention -> inline.label
    }

    private fun JsonNode.requiredText(field: String): String = optionalText(field)
        ?: throw NotionBlockMappingException("required $field is missing")

    private fun JsonNode.optionalText(field: String): String? = get(field)
        ?.takeIf(JsonNode::isString)
        ?.stringValue()
        ?.takeIf(String::isNotBlank)

    private fun JsonNode.requiredBoolean(field: String): Boolean = optionalBoolean(field)
        ?: throw NotionBlockMappingException("required $field is missing")

    private fun JsonNode.optionalBoolean(field: String): Boolean? = get(field)
        ?.takeIf(JsonNode::isBoolean)
        ?.asBoolean()

    private fun JsonNode.requiredPositiveInt(field: String): Int = get(field)
        ?.takeIf(JsonNode::isInt)
        ?.intValue()
        ?.takeIf { it > 0 }
        ?: throw NotionBlockMappingException("$field must be a positive integer")

    private fun JsonNode.optionalDouble(field: String): Double? = get(field)
        ?.takeIf(JsonNode::isNumber)
        ?.doubleValue()

    private fun JsonNode.requiredObject(field: String): JsonNode = get(field)
        ?.takeIf(JsonNode::isObject)
        ?: throw NotionBlockMappingException("required $field object is missing")

    private fun JsonNode.optionalObject(field: String): JsonNode? = get(field)?.takeIf(JsonNode::isObject)

    private fun JsonNode.requiredArray(field: String): List<JsonNode> = get(field)
        ?.takeIf(JsonNode::isArray)
        ?.toList()
        ?: throw NotionBlockMappingException("required $field array is missing")

    private fun JsonNode.optionalArray(field: String): List<JsonNode>? = get(field)
        ?.takeIf(JsonNode::isArray)
        ?.toList()

    private data class SplitColor(
        val foreground: ColorToken? = null,
        val background: ColorToken? = null,
    )
}

internal class NotionBlockMappingException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
