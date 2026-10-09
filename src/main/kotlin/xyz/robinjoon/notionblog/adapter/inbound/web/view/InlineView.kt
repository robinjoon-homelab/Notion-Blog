package xyz.robinjoon.notionblog.adapter.inbound.web.view

sealed interface InlineView {
    val kind: InlineKind
    val annotations: InlineAnnotationsView
    val link: LinkView?
}

enum class InlineKind { TEXT, EQUATION, MENTION }

data class TextInlineView(
    val text: String,
    override val annotations: InlineAnnotationsView,
    override val link: LinkView?,
) : InlineView {
    override val kind = InlineKind.TEXT
}

data class EquationInlineView(
    val expression: String,
    override val annotations: InlineAnnotationsView,
) : InlineView {
    override val kind = InlineKind.EQUATION
    override val link: LinkView? = null
}

data class MentionInlineView(
    val label: String,
    val mentionKind: MentionKindView,
    override val annotations: InlineAnnotationsView,
    override val link: LinkView?,
) : InlineView {
    override val kind = InlineKind.MENTION
}

data class InlineAnnotationsView(
    val bold: Boolean,
    val italic: Boolean,
    val strikethrough: Boolean,
    val underline: Boolean,
    val code: Boolean,
    val classes: List<String>,
)

enum class MentionKindView {
    DOCUMENT,
    USER,
    DATE,
    TEMPLATE,
    DATABASE,
    LINK_PREVIEW,
    OTHER,
}

sealed interface LinkView {
    val href: String
}

data class InternalLinkView(
    override val href: String,
) : LinkView

data class ExternalLinkView(
    override val href: String,
) : LinkView
