package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.inline.MentionKind
import xyz.robinjoon.notionblog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI

internal class NotionRichTextMapper(
    private val sourceId: SourceId,
) {
    fun map(values: JsonNode): List<InlineContent> {
        if (!values.isArray) throw NotionBlockMappingException("rich text must be an array")
        return values.toList().map(::inline)
    }

    fun required(
        payload: JsonNode,
        field: String = "rich_text",
    ): List<InlineContent> = payload.requiredArray(field).map(::inline)

    fun optional(
        payload: JsonNode,
        field: String,
    ): List<InlineContent> = payload.optionalArray(field)?.map(::inline).orEmpty()

    private fun inline(node: JsonNode): InlineContent {
        val annotations = NotionStyleMapper.annotations(node.requiredObject("annotations"))
        return when (node.requiredText("type")) {
            "text" -> {
                val text = node.requiredObject("text")
                InlineContent.Text(
                    text =
                        text.get("content")?.takeIf(JsonNode::isString)?.stringValue()
                            ?: throw NotionBlockMappingException("text content must be a string"),
                    annotations = annotations,
                    link = text.optionalObject("link")?.safeUri("url")?.let(LinkTarget::ExternalUrl),
                )
            }

            "equation" -> {
                InlineContent.Equation(
                    expression = node.requiredObject("equation").requiredText("expression"),
                    annotations = annotations,
                )
            }

            "mention" -> {
                mention(node, annotations)
            }

            else -> {
                throw NotionBlockMappingException("rich text type is unsupported")
            }
        }
    }

    private fun mention(
        node: JsonNode,
        annotations: TextAnnotations,
    ): InlineContent.Mention {
        val mention = node.requiredObject("mention")
        val type = mention.requiredText("type")
        return InlineContent.Mention(
            label = node.requiredText("plain_text"),
            kind = mentionKind(type),
            annotations = annotations,
            target = mentionTarget(mention, node.optionalText("href")?.let(::parseSafeUri)),
        )
    }

    private fun mentionTarget(
        mention: JsonNode,
        href: URI?,
    ): LinkTarget? =
        when (mention.requiredText("type")) {
            "page" -> {
                LinkTarget.SourceDocument(
                    reference = pageSourceReference(mention.requiredObject("page").requiredText("id")),
                    originalUrl = href,
                )
            }

            "link_preview" -> {
                LinkTarget.ExternalUrl(href ?: mention.requiredObject("link_preview").safeUri("url"))
            }

            else -> {
                href?.let(LinkTarget::ExternalUrl)
            }
        }

    private fun mentionKind(type: String): MentionKind =
        when (type) {
            "page" -> MentionKind.DOCUMENT
            "database" -> MentionKind.DATABASE
            "date" -> MentionKind.DATE
            "template_mention" -> MentionKind.TEMPLATE
            "link_preview" -> MentionKind.LINK_PREVIEW
            "user" -> MentionKind.USER
            else -> MentionKind.OTHER
        }

    private fun pageSourceReference(externalId: String): SourceDocumentRef =
        SourceDocumentRef(sourceId, NotionIdNormalizer.normalize(externalId))
}
