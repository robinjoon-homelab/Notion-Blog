package xyz.robinjoon.notionblog.blog.domain.post

import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource

// 원본에 업로드된 파일은 만료되므로 애플리케이션이 자체 사본을 보관해야 하는 파일이다. 순회 로직을 미디어 값이 아닌 글 쪽에 두어
// block·content·media 사이에 순환이 생기지 않게 한다.
fun BlockTree.sourceHostedMedia(): Set<MediaSource.SourceHosted> =
    buildSet {
        roots.forEach { node -> collect(node, this) }
    }

private fun collect(
    node: BlockNode,
    media: MutableSet<MediaSource.SourceHosted>,
) {
    media += sources(node.content).filterIsInstance<MediaSource.SourceHosted>()
    node.children.forEach { child -> collect(child, media) }
}

private fun sources(content: BlockContent): List<MediaSource?> =
    when (content) {
        is MediaBlockContent.Media -> listOf(content.source)
        is TextBlockContent.Callout -> listOf(content.icon.mediaSource())
        is LayoutBlockContent.TabItem -> listOf(content.icon.mediaSource())
        is DataViewContent -> content.data.rows.flatMap { row -> listOf(row.icon.mediaSource(), row.cover) }
        else -> emptyList()
    }

private fun BlockIcon?.mediaSource(): MediaSource? = (this as? BlockIcon.Media)?.source
