package xyz.robinjoon.notionblog.domain.post.block.inline

import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import java.net.URI

data class TextAnnotations
    // quality-exception: Seven independent text styles preserve the normalized snapshot and inline rendering contract.
    @Suppress("LongParameterList")
    constructor(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikethrough: Boolean = false,
        val underline: Boolean = false,
        val code: Boolean = false,
        val foreground: ColorToken? = null,
        val background: ColorToken? = null,
    )

enum class MentionKind {
    DOCUMENT,
    USER,
    DATE,
    TEMPLATE,
    DATABASE,
    LINK_PREVIEW,
    OTHER,
}

sealed interface LinkTarget {
    data class ExternalUrl(
        val url: URI,
    ) : LinkTarget

    data class SourceDocument(
        val reference: SourceDocumentRef,
        val originalUrl: URI?,
    ) : LinkTarget
}

sealed interface InlineContent {
    val annotations: TextAnnotations

    data class Text(
        val text: String,
        override val annotations: TextAnnotations = TextAnnotations(),
        val link: LinkTarget? = null,
    ) : InlineContent

    data class Equation(
        val expression: String,
        override val annotations: TextAnnotations = TextAnnotations(),
    ) : InlineContent

    data class Mention(
        val label: String,
        val kind: MentionKind,
        override val annotations: TextAnnotations = TextAnnotations(),
        val target: LinkTarget? = null,
    ) : InlineContent
}
