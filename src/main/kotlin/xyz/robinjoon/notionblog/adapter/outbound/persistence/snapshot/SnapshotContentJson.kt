package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent

internal fun kindOf(content: BlockContent): String =
    when (content) {
        is TextBlockContent -> kindOfText(content)
        is ListBlockContent -> kindOfList(content)
        is LayoutBlockContent -> kindOfLayout(content)
        is ReferenceBlockContent -> kindOfReference(content)
        is MediaBlockContent -> kindOfMediaBlock(content)
        is ReusableBlockContent -> kindOfReusable(content)
        is SpecialBlockContent -> kindOfSpecial(content)
        is DataViewContent -> "data_view"
        is UnsupportedBlockContent -> "unsupported"
    }

internal fun toJsonContent(content: BlockContent): ObjectNode =
    when (content) {
        is TextBlockContent -> toJsonText(content)
        is ListBlockContent -> toJsonList(content)
        is LayoutBlockContent -> toJsonLayout(content)
        is ReferenceBlockContent -> toJsonReference(content)
        is MediaBlockContent -> toJsonMediaBlock(content)
        is ReusableBlockContent -> toJsonReusable(content)
        is SpecialBlockContent -> toJsonSpecial(content)
        is DataViewContent -> toJsonDataView(content)
        is UnsupportedBlockContent -> objectNode().put("blockType", content.blockType)
    }

internal fun String.isKnownBlockKind(): Boolean = this in knownBlockKinds

internal fun fromJsonContent(
    kind: String,
    content: ObjectNode,
): BlockContent =
    when (kind) {
        in textBlockKinds -> fromJsonText(kind, content)
        in listBlockKinds -> fromJsonList(kind, content)
        in layoutBlockKinds -> fromJsonLayout(kind, content)
        in referenceBlockKinds -> fromJsonReference(kind, content)
        in mediaBlockKinds -> fromJsonMediaBlock(kind, content)
        in reusableBlockKinds -> fromJsonReusable(kind, content)
        in specialBlockKinds -> fromJsonSpecial(kind, content)
        "data_table" -> fromJsonLegacyDataTable(content)
        "data_view" -> fromJsonDataView(content)
        "unsupported" -> UnsupportedBlockContent(content.requiredText("blockType", kind))
        else -> UnsupportedBlockContent(kind)
    }

private val knownBlockKinds =
    textBlockKinds +
        listBlockKinds +
        layoutBlockKinds +
        referenceBlockKinds +
        mediaBlockKinds +
        reusableBlockKinds +
        specialBlockKinds +
        setOf("data_table", "data_view", "unsupported")
