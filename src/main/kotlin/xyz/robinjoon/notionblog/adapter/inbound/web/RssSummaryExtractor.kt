package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent

class RssSummaryExtractor {
    fun extract(post: Post): String {
        val body =
            normalize(
                post.content.roots
                    .asSequence()
                    .flatMap(::text)
                    .joinToString(" "),
            )
        val summary = body.ifEmpty { RssXmlText.postTitle(normalize(post.title)) }
        return if (summary.codePointCount(0, summary.length) <= MAX_CODE_POINTS) {
            summary
        } else {
            summary.substring(0, summary.offsetByCodePoints(0, MAX_CODE_POINTS - 1)) + "…"
        }
    }

    private fun text(node: BlockNode): Sequence<String> =
        sequence {
            val richText =
                when (val content = node.content) {
                    is TextBlockContent.Paragraph -> content.richText
                    is TextBlockContent.Heading -> content.richText
                    is TextBlockContent.Quote -> content.richText
                    is TextBlockContent.Callout -> content.richText
                    is TextBlockContent.Toggle -> content.richText
                    is ListBlockContent -> content.richText
                    else -> emptyList()
                }
            if (richText.isNotEmpty()) {
                yield(
                    richText.joinToString("") { inline ->
                        when (inline) {
                            is InlineContent.Text -> inline.text
                            is InlineContent.Mention -> inline.label
                            is InlineContent.Equation -> inline.expression
                        }
                    },
                )
            }
            node.children.forEach { yieldAll(text(it)) }
        }

    private fun normalize(value: String): String = RssXmlText.clean(value).replace(whitespace, " ").trim()

    private companion object {
        const val MAX_CODE_POINTS = 280
        val whitespace = Regex("(?U)\\s+")
    }
}
