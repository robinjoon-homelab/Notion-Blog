package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.domain.post.block.style.BlockStyle
import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken

internal object NotionStyleMapper {
    fun annotations(node: JsonNode): TextAnnotations =
        TextAnnotations(
            bold = node.optionalBoolean("bold") ?: false,
            italic = node.optionalBoolean("italic") ?: false,
            strikethrough = node.optionalBoolean("strikethrough") ?: false,
            underline = node.optionalBoolean("underline") ?: false,
            code = node.optionalBoolean("code") ?: false,
            foreground = color(node.optionalText("color"))?.foreground,
            background = color(node.optionalText("color"))?.background,
        )

    fun block(payload: JsonNode): BlockStyle {
        val color = color(payload.optionalText("color"))
        return BlockStyle(foreground = color?.foreground, background = color?.background)
    }

    fun nativeIconColor(value: String): ColorToken? {
        val split = color(value)
        if (split?.background != null) {
            throw NotionBlockMappingException("native icon color must be a foreground color")
        }
        return split?.foreground
    }

    private fun color(value: String?): SplitColor? =
        when (value) {
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

    private data class SplitColor(
        val foreground: ColorToken? = null,
        val background: ColorToken? = null,
    )
}
