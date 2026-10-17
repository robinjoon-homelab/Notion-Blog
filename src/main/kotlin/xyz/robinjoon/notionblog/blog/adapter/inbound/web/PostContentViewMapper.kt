package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.BlockIconView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.CustomEmojiIconView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.EmojiIconView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.EquationInlineView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ExternalLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.InlineAnnotationsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.InlineView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.InternalLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.LinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MediaIconView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MentionInlineView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MentionKindView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NativeIconView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.TextInlineView
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.LinkResolution
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource

internal class PostContentViewMapper(
    private val references: ContentReferences,
    private val revisionId: PostRevisionId,
    private val mediaPath: String,
) {
    fun withLink(
        inline: InlineView,
        link: LinkView?,
    ): InlineView =
        when (inline) {
            is TextInlineView -> inline.copy(link = link)
            is MentionInlineView -> inline.copy(link = link)
            is EquationInlineView -> inline
        }

    fun inlineViews(inlines: List<InlineContent>): List<InlineView> =
        inlines.map { inline ->
            when (inline) {
                is InlineContent.Text -> {
                    TextInlineView(inline.text, annotationsView(inline.annotations), inline.link?.let { linkView(it) })
                }

                is InlineContent.Equation -> {
                    EquationInlineView(inline.expression, annotationsView(inline.annotations))
                }

                is InlineContent.Mention -> {
                    MentionInlineView(
                        inline.label,
                        MentionKindView.valueOf(inline.kind.name),
                        annotationsView(inline.annotations),
                        inline.target?.let { linkView(it) },
                    )
                }
            }
        }

    private fun annotationsView(annotations: TextAnnotations): InlineAnnotationsView =
        InlineAnnotationsView(
            annotations.bold,
            annotations.italic,
            annotations.strikethrough,
            annotations.underline,
            annotations.code,
            BlockStyleViewMapper.colorClasses(annotations.foreground, annotations.background),
        )

    fun linkView(target: LinkTarget): LinkView? =
        when (target) {
            is LinkTarget.ExternalUrl -> {
                WebContentUrls.external(target.url)?.let(::ExternalLinkView)
            }

            is LinkTarget.SourceDocument -> {
                when (val resolution = references.links[target]) {
                    is LinkResolution.Internal -> InternalLinkView("/posts/${resolution.postId.value}")
                    is LinkResolution.External -> WebContentUrls.external(resolution.url)?.let(::ExternalLinkView)
                    LinkResolution.Unlinked, null -> null
                }
            }
        }

    fun mediaUrl(source: MediaSource): String? =
        when (source) {
            is MediaSource.External -> {
                WebContentUrls.external(source.url)
            }

            is MediaSource.SourceHosted -> {
                ownedMediaUrl(source)
            }
        }

    // 원본 서명 URL은 공개 HTML에 내보내지 않는다. 보관본이 없으면 null로 두어 누락 안내를 보인다.
    // 같은 원본 파일명도 리비전마다 내용이 다를 수 있어, 이 매퍼가 그리는 리비전의 보관본만 찾는다.
    private fun ownedMediaUrl(source: MediaSource.SourceHosted): String? =
        references.media.keyFor(revisionId, source)?.let { key -> "$mediaPath${key.value}" }

    fun iconView(icon: BlockIcon?): BlockIconView? =
        when (icon) {
            null -> null
            is BlockIcon.Emoji -> EmojiIconView(icon.value)
            is BlockIcon.Media -> MediaIconView(mediaUrl(icon.source))
            is BlockIcon.Native -> NativeIconView(icon.name, BlockStyleViewMapper.colorClasses(icon.color, null).singleOrNull())
            is BlockIcon.CustomEmoji -> CustomEmojiIconView(icon.name, mediaUrl(icon.source))
        }

    fun plainText(inlines: List<InlineContent>): String =
        inlines.joinToString("") { inline ->
            when (inline) {
                is InlineContent.Text -> inline.text
                is InlineContent.Equation -> inline.expression
                is InlineContent.Mention -> inline.label
            }
        }
}
