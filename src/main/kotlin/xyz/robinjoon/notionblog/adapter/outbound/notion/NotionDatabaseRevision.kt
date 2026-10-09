package xyz.robinjoon.notionblog.adapter.outbound.notion

import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.security.MessageDigest
import java.util.HexFormat

internal object NotionDatabaseRevision {
    private val fingerprintMapper = JsonMapper.builder().build()

    fun append(
        pageRevision: SourceRevision,
        databases: List<BlockNode>,
    ): SourceRevision {
        if (databases.isEmpty()) return pageRevision
        // Row edits and publication changes need not update the parent page's revision.
        val encoded = fingerprintMapper.writeValueAsBytes(databases.map(::databaseFingerprint))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(encoded)
        return SourceRevision("${pageRevision.value}:inline-db-v2:${HexFormat.of().formatHex(fingerprint)}")
    }

    private fun databaseFingerprint(block: BlockNode): List<Any?> =
        listOf(
            block.id.value,
            block.style.let { listOf(it.foreground?.name, it.background?.name, it.alignment?.name, it.width?.ratio, it.variant?.value) },
            contentFingerprint(block),
            block.children.map(::databaseFingerprint),
        )

    private fun contentFingerprint(block: BlockNode): List<Any?> =
        when (val content = block.content) {
            is ReferenceBlockContent.DatabaseLink -> {
                listOf(
                    "database",
                    content.reference.sourceId.value,
                    content.reference.externalId,
                    content.originalUrl?.toString(),
                    content.title,
                )
            }

            LayoutBlockContent.TabContainer -> {
                listOf("tabs")
            }

            is LayoutBlockContent.TabItem -> {
                listOf("tab", content.title.map(::inlineFingerprint), iconFingerprint(content.icon))
            }

            is DataViewContent -> {
                dataViewFingerprint(content)
            }

            is UnsupportedBlockContent -> {
                listOf("unsupported", content.blockType)
            }

            else -> {
                throw SourceMappingException("Notion inline database contains an unexpected block")
            }
        }

    private fun dataViewFingerprint(content: DataViewContent): List<Any?> =
        listOf(
            viewOptionsFingerprint(content),
            content.data.title,
            content.data.titleColumnIndex,
            content.data.columns.map { listOf(it.name, it.widthPixels, it.wrap) },
            content.data.rows.map { row ->
                listOf(
                    row.cells.map { cell -> cell.map(::inlineFingerprint) },
                    linkFingerprint(row.link),
                    iconFingerprint(row.icon),
                    mediaFingerprint(row.cover),
                )
            },
        )

    private fun viewOptionsFingerprint(content: DataViewContent): List<Any?> =
        when (content) {
            is DataViewContent.Table -> {
                content.options.let {
                    listOf("table", it.wrapCells, it.frozenColumns, it.showVerticalLines)
                }
            }

            is DataViewContent.ListView -> {
                listOf("list")
            }

            is DataViewContent.Gallery -> {
                content.options.let {
                    listOf("gallery", it.size.name, it.aspect.name, it.layout.name)
                }
            }
        }

    private fun inlineFingerprint(inline: InlineContent): List<Any?> =
        listOf(
            when (inline) {
                is InlineContent.Text -> listOf("text", inline.text, linkFingerprint(inline.link))
                is InlineContent.Equation -> listOf("equation", inline.expression)
                is InlineContent.Mention -> listOf("mention", inline.label, inline.kind.name, linkFingerprint(inline.target))
            },
            inline.annotations.let {
                listOf(it.bold, it.italic, it.strikethrough, it.underline, it.code, it.foreground?.name, it.background?.name)
            },
        )

    private fun linkFingerprint(link: LinkTarget?): List<String?>? =
        when (link) {
            null -> {
                null
            }

            is LinkTarget.ExternalUrl -> {
                listOf("external", link.url.toString())
            }

            is LinkTarget.SourceDocument -> {
                listOf(
                    "document",
                    link.reference.sourceId.value,
                    link.reference.externalId,
                    link.originalUrl?.toString(),
                )
            }
        }

    private fun iconFingerprint(icon: BlockIcon?): List<Any?>? =
        when (icon) {
            null -> null
            is BlockIcon.Emoji -> listOf("emoji", icon.value)
            is BlockIcon.Native -> listOf("native", icon.name, icon.color?.name)
            is BlockIcon.CustomEmoji -> listOf("custom", icon.externalId, icon.name, mediaFingerprint(icon.source))
            is BlockIcon.Media -> listOf("media", mediaFingerprint(icon.source))
        }

    private fun mediaFingerprint(source: MediaSource?): List<String?>? =
        when (source) {
            null -> null
            is MediaSource.External -> listOf("external", source.url.toString())
            is MediaSource.SourceHosted -> listOf("hosted", source.url.toString(), source.expiresAt?.toString())
        }
}
