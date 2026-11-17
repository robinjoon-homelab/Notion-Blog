package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.MentionKind
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
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
                    link = text.optionalObject("link")?.let { LinkTarget.ExternalUrl(richTextUrl(it.requiredText("url"))) },
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
            target = mentionTarget(mention, node.optionalText("href")?.let(::richTextUrl)),
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
                LinkTarget.ExternalUrl(href ?: richTextUrl(mention.requiredObject("link_preview").requiredText("url")))
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

    // Notion 문서 안의 경로만 원본 주소로 바꾼다. 블록 링크의 query와 fragment는 보존한다.
    private fun richTextUrl(value: String): URI {
        val path = value.substringBefore('?').substringBefore('#')
        if (!path.startsWith('/')) return parseSafeUri(value)
        val pageId = path.removePrefix("/").removePrefix("p/")
        if (NotionIdNormalizer.normalizeOrNull(pageId) == null) return parseSafeUri(value)
        return parseSafeUri("https://app.notion.com/p/$pageId${value.substring(path.length)}")
    }
}
