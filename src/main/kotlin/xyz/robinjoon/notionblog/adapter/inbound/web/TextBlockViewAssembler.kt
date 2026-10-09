package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockEquationView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockStyleView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BulletedListItemView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.CalloutView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.CodeLanguageView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.CodeView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.HeadingLevelView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.HeadingView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ListItemView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.NumberedListFormatView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.NumberedListItemView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ParagraphView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.QuoteView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TodoListItemView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ToggleView
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent

internal class TextBlockViewAssembler(
    private val contentMapper: PostContentViewMapper,
) {
    fun assemble(
        content: TextBlockContent,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): BlockView =
        when (content) {
            is TextBlockContent.Paragraph -> ParagraphView(id, contentMapper.inlineViews(content.richText), style, children)
            is TextBlockContent.Heading -> heading(content, id, style, children)
            is TextBlockContent.Quote -> QuoteView(id, contentMapper.inlineViews(content.richText), style, children)
            is TextBlockContent.Toggle -> ToggleView(id, contentMapper.inlineViews(content.richText), style, children)
            is TextBlockContent.Callout -> callout(content, id, style, children)
            is TextBlockContent.Code -> code(content, id, style, children)
            is TextBlockContent.Equation -> BlockEquationView(id, content.expression, style, children)
        }

    private fun heading(
        content: TextBlockContent.Heading,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): HeadingView =
        HeadingView(
            id,
            HeadingLevelView.valueOf(content.level.name),
            contentMapper.inlineViews(content.richText),
            content.isToggleable,
            style,
            children,
        )

    private fun callout(
        content: TextBlockContent.Callout,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): CalloutView = CalloutView(id, contentMapper.iconView(content.icon), contentMapper.inlineViews(content.richText), style, children)

    private fun code(
        content: TextBlockContent.Code,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): CodeView =
        CodeView(
            id,
            contentMapper.plainText(content.richText),
            codeLanguage(content.language),
            contentMapper.inlineViews(content.caption),
            style,
            children,
        )

    fun listItem(
        content: ListBlockContent,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): ListItemView =
        when (content) {
            is ListBlockContent.BulletedItem -> {
                BulletedListItemView(id, contentMapper.inlineViews(content.richText), style, children)
            }

            is ListBlockContent.NumberedItem -> {
                NumberedListItemView(
                    id,
                    contentMapper.inlineViews(content.richText),
                    NumberedListFormatView.valueOf(content.displayFormat.name),
                    style,
                    children,
                )
            }

            is ListBlockContent.ToDoItem -> {
                TodoListItemView(id, contentMapper.inlineViews(content.richText), content.checked, style, children)
            }
        }

    private fun codeLanguage(language: String): CodeLanguageView =
        CodeLanguageView(
            when (language.lowercase()) {
                "kotlin", "java", "javascript", "typescript", "python", "json", "bash", "sql", "html", "css" -> {
                    "language-${language.lowercase()}"
                }

                else -> {
                    "language-plain"
                }
            },
        )
}
