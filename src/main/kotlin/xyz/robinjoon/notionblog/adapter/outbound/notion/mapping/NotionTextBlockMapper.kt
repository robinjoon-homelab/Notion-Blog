package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.NumberedListFormat
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent

internal class NotionTextBlockMapper(
    private val richText: NotionRichTextMapper,
    private val media: NotionMediaMapper,
) {
    fun map(
        type: String,
        payload: JsonNode,
    ): BlockContent? =
        headingContent(type, payload)
            ?: proseContent(type, payload)
            ?: listContent(type, payload)
            ?: literalContent(type, payload)

    private fun headingContent(
        type: String,
        payload: JsonNode,
    ): TextBlockContent.Heading? =
        when (type) {
            "heading_1" -> heading(payload, HeadingLevel.ONE)
            "heading_2" -> heading(payload, HeadingLevel.TWO)
            "heading_3" -> heading(payload, HeadingLevel.THREE)
            "heading_4" -> heading(payload, HeadingLevel.FOUR)
            else -> null
        }

    private fun proseContent(
        type: String,
        payload: JsonNode,
    ): BlockContent? =
        when (type) {
            "paragraph" -> TextBlockContent.Paragraph(richText.required(payload))
            "toggle" -> TextBlockContent.Toggle(richText.required(payload))
            "quote" -> TextBlockContent.Quote(richText.required(payload))
            "callout" -> TextBlockContent.Callout(richText.required(payload), media.icon(payload.optionalObject("icon")))
            "template" -> ReusableBlockContent.Template(richText.required(payload))
            else -> null
        }

    private fun listContent(
        type: String,
        payload: JsonNode,
    ): ListBlockContent? =
        when (type) {
            "bulleted_list_item" -> ListBlockContent.BulletedItem(richText.required(payload))
            "numbered_list_item" -> numberedListItem(payload)
            "to_do" -> ListBlockContent.ToDoItem(richText.required(payload), payload.requiredBoolean("checked"))
            else -> null
        }

    private fun literalContent(
        type: String,
        payload: JsonNode,
    ): TextBlockContent? =
        when (type) {
            "code" -> {
                TextBlockContent.Code(
                    richText = richText.required(payload),
                    language = payload.requiredText("language"),
                    caption = richText.optional(payload, "caption"),
                )
            }

            "equation" -> {
                TextBlockContent.Equation(payload.requiredText("expression"))
            }

            else -> {
                null
            }
        }

    private fun heading(
        payload: JsonNode,
        level: HeadingLevel,
    ) = TextBlockContent.Heading(
        level = level,
        richText = richText.required(payload),
        isToggleable = payload.requiredBoolean("is_toggleable"),
    )

    private fun numberedListItem(payload: JsonNode): ListBlockContent.NumberedItem {
        val hasExplicitStart = payload.has("list_start_index")
        val hasExplicitFormat = payload.has("list_format")
        return ListBlockContent.NumberedItem(
            richText = richText.required(payload),
            startNumber = if (hasExplicitStart) payload.requiredPositiveInt("list_start_index") else 1,
            displayFormat = if (hasExplicitFormat) numberedListFormat(payload.requiredText("list_format")) else NumberedListFormat.DECIMAL,
            startsNewList = hasExplicitStart || hasExplicitFormat,
        )
    }

    private fun numberedListFormat(value: String): NumberedListFormat =
        when (value) {
            "numbers" -> NumberedListFormat.DECIMAL
            "letters" -> NumberedListFormat.LOWER_ALPHA
            "roman" -> NumberedListFormat.LOWER_ROMAN
            else -> throw NotionBlockMappingException("numbered list format is unsupported")
        }
}
