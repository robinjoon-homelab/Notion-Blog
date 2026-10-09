package xyz.robinjoon.notionblog.domain.post.block.content

import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken

sealed interface TextBlockContent : BlockContent {
    val richText: List<InlineContent>

    data class Paragraph(
        override val richText: List<InlineContent>,
    ) : TextBlockContent

    data class Heading(
        val level: HeadingLevel,
        override val richText: List<InlineContent>,
        val isToggleable: Boolean = false,
    ) : TextBlockContent

    data class Quote(
        override val richText: List<InlineContent>,
    ) : TextBlockContent

    data class Toggle(
        override val richText: List<InlineContent>,
    ) : TextBlockContent

    data class Callout(
        override val richText: List<InlineContent>,
        val icon: BlockIcon?,
    ) : TextBlockContent

    data class Code(
        override val richText: List<InlineContent>,
        val language: String,
        val caption: List<InlineContent> = emptyList(),
    ) : TextBlockContent {
        init {
            require(language.isNotBlank()) { "code language must not be blank" }
        }
    }

    data class Equation(
        val expression: String,
    ) : TextBlockContent {
        override val richText: List<InlineContent> = emptyList()

        init {
            require(expression.isNotBlank()) { "equation expression must not be blank" }
        }
    }
}

enum class HeadingLevel {
    ONE,
    TWO,
    THREE,
    FOUR,
}

sealed interface BlockIcon {
    data class Emoji(
        val value: String,
    ) : BlockIcon {
        init {
            require(value.isNotBlank()) { "emoji icon must not be blank" }
        }
    }

    data class Media(
        val source: MediaSource,
    ) : BlockIcon

    data class Native(
        val name: String,
        val color: ColorToken?,
    ) : BlockIcon {
        init {
            require(name.isNotBlank()) { "native icon name must not be blank" }
        }
    }

    data class CustomEmoji(
        val externalId: String,
        val name: String,
        val source: MediaSource.External,
    ) : BlockIcon {
        init {
            require(externalId.isNotBlank()) { "custom emoji external id must not be blank" }
            require(name.isNotBlank()) { "custom emoji name must not be blank" }
        }
    }
}
