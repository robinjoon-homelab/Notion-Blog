package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockStyleView
import xyz.robinjoon.notionblog.domain.post.block.style.Alignment
import xyz.robinjoon.notionblog.domain.post.block.style.BlockStyle
import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken

internal object BlockStyleViewMapper {
    fun map(style: BlockStyle): BlockStyleView =
        BlockStyleView(
            buildList {
                addAll(colorClasses(style.foreground, style.background))
                when (style.alignment) {
                    Alignment.LEFT -> add("notion-align-left")
                    Alignment.CENTER -> add("notion-align-center")
                    Alignment.RIGHT -> add("notion-align-right")
                    null -> Unit
                }
                style.width?.ratio?.let { ratio ->
                    add(
                        when {
                            ratio >= 0.99 -> "notion-width-full"
                            ratio >= 0.66 -> "notion-width-wide"
                            ratio >= 0.33 -> "notion-width-standard"
                            else -> "notion-width-narrow"
                        },
                    )
                }
                when (style.variant?.value) {
                    "default" -> add("notion-variant-default")
                    "subtle" -> add("notion-variant-subtle")
                    "emphasis" -> add("notion-variant-emphasis")
                    else -> Unit
                }
            },
        )

    fun colorClasses(
        foreground: ColorToken?,
        background: ColorToken?,
    ): List<String> =
        buildList {
            foreground?.let { add("notion-color-${it.name.lowercase()}") }
            background?.let { add("notion-background-${it.name.lowercase()}") }
        }
}
