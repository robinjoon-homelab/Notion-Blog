package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockStyleView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BookmarkView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.EmbedView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.LinkPreviewView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.MediaTypeView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.MediaView
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent

internal class MediaBlockViewAssembler(
    private val contentMapper: PostContentViewMapper,
) {
    fun assemble(
        content: MediaBlockContent,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): BlockView =
        when (content) {
            is MediaBlockContent.Media -> {
                MediaView(
                    id,
                    MediaTypeView.valueOf(content.mediaType.name),
                    contentMapper.mediaUrl(content.source),
                    content.fileName,
                    contentMapper.inlineViews(content.caption),
                    style,
                    children,
                )
            }

            is MediaBlockContent.Bookmark -> {
                BookmarkView(
                    id,
                    WebContentUrls.external(content.url),
                    contentMapper.inlineViews(content.caption),
                    style,
                    children,
                )
            }

            is MediaBlockContent.LinkPreview -> {
                LinkPreviewView(id, WebContentUrls.external(content.url), style, children)
            }

            is MediaBlockContent.Embed -> {
                embed(content, id, style, children)
            }
        }

    private fun embed(
        content: MediaBlockContent.Embed,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): EmbedView {
        val canonical = WebContentUrls.embed(content.url)
        return EmbedView(
            id,
            canonical?.provider,
            canonical?.url,
            WebContentUrls.external(content.url),
            contentMapper.inlineViews(content.caption),
            style,
            children,
        )
    }
}
