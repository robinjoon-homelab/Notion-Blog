package xyz.robinjoon.notionblog.adapter.inbound.web.view

import kotlin.math.roundToInt

sealed interface BlockView {
    val id: String
    val kind: BlockKind
    val style: BlockStyleView
    val children: List<BlockView>
}

data class BlockStyleView(
    val classes: List<String>,
)

enum class BlockKind {
    PARAGRAPH,
    HEADING,
    QUOTE,
    TOGGLE,
    CALLOUT,
    CODE,
    EQUATION,
    LIST,
    LIST_ITEM,
    DIVIDER,
    COLUMN_LIST,
    COLUMN,
    TAB_CONTAINER,
    TAB_ITEM,
    TABLE,
    TABLE_ROW,
    DATA_TABLE,
    DATA_LIST,
    DATA_GALLERY,
    MEDIA,
    BOOKMARK,
    LINK_PREVIEW,
    EMBED,
    CHILD_POST,
    DOCUMENT_LINK,
    DATABASE_LINK,
    BREADCRUMB,
    TABLE_OF_CONTENTS,
    SYNCHRONIZED,
    TEMPLATE,
    MEETING_NOTES,
    UNSUPPORTED,
}

data class ParagraphView(
    override val id: String,
    val content: List<InlineView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.PARAGRAPH
}

data class HeadingView(
    override val id: String,
    val level: HeadingLevelView,
    val content: List<InlineView>,
    val toggleable: Boolean,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.HEADING
}

enum class HeadingLevelView { ONE, TWO, THREE, FOUR }

data class QuoteView(
    override val id: String,
    val content: List<InlineView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.QUOTE
}

data class ToggleView(
    override val id: String,
    val content: List<InlineView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.TOGGLE
}

data class CalloutView(
    override val id: String,
    val icon: BlockIconView?,
    val content: List<InlineView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.CALLOUT
}

data class CodeView(
    override val id: String,
    val content: String,
    val language: CodeLanguageView,
    val caption: List<InlineView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.CODE
}

data class CodeLanguageView(
    val cssClass: String,
)

data class BlockEquationView(
    override val id: String,
    val expression: String,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.EQUATION
}

data class ListView(
    override val id: String,
    val type: ListTypeView,
    val startNumber: Int?,
    val numberFormat: NumberedListFormatView?,
    val items: List<ListItemView>,
    override val style: BlockStyleView,
) : BlockView {
    override val kind = BlockKind.LIST
    override val children: List<BlockView> = emptyList()
}

enum class ListTypeView { BULLETED, NUMBERED, TODO }

sealed interface ListItemView : BlockView {
    val content: List<InlineView>
}

data class BulletedListItemView(
    override val id: String,
    override val content: List<InlineView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : ListItemView {
    override val kind = BlockKind.LIST_ITEM
}

data class NumberedListItemView(
    override val id: String,
    override val content: List<InlineView>,
    val displayFormat: NumberedListFormatView,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : ListItemView {
    override val kind = BlockKind.LIST_ITEM
}

data class TodoListItemView(
    override val id: String,
    override val content: List<InlineView>,
    val checked: Boolean,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : ListItemView {
    override val kind = BlockKind.LIST_ITEM
}

enum class NumberedListFormatView { DECIMAL, LOWER_ALPHA, UPPER_ALPHA, LOWER_ROMAN, UPPER_ROMAN }

data class DividerView(
    override val id: String,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.DIVIDER
}

data class ColumnListView(
    override val id: String,
    val columns: List<ColumnView>,
    override val style: BlockStyleView,
) : BlockView {
    override val kind = BlockKind.COLUMN_LIST
    override val children: List<BlockView> = emptyList()
}

data class ColumnView(
    override val id: String,
    val widthClass: ColumnWidthClass?,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.COLUMN
}

enum class ColumnWidthClass(
    val cssClass: String,
) {
    PERCENT_5("notion-column-width-5"),
    PERCENT_10("notion-column-width-10"),
    PERCENT_15("notion-column-width-15"),
    PERCENT_20("notion-column-width-20"),
    PERCENT_25("notion-column-width-25"),
    PERCENT_30("notion-column-width-30"),
    PERCENT_35("notion-column-width-35"),
    PERCENT_40("notion-column-width-40"),
    PERCENT_45("notion-column-width-45"),
    PERCENT_50("notion-column-width-50"),
    PERCENT_55("notion-column-width-55"),
    PERCENT_60("notion-column-width-60"),
    PERCENT_65("notion-column-width-65"),
    PERCENT_70("notion-column-width-70"),
    PERCENT_75("notion-column-width-75"),
    PERCENT_80("notion-column-width-80"),
    PERCENT_85("notion-column-width-85"),
    PERCENT_90("notion-column-width-90"),
    PERCENT_95("notion-column-width-95"),
    PERCENT_100("notion-column-width-100"),
    ;

    companion object {
        fun fromRatio(ratio: Double?): ColumnWidthClass? =
            ratio?.let {
                entries[(it * entries.size).roundToInt().coerceIn(1, entries.size) - 1]
            }
    }
}

data class TabContainerView(
    override val id: String,
    val tabs: List<TabItemView>,
    override val style: BlockStyleView,
    val collapseSingleTab: Boolean = false,
) : BlockView {
    override val kind = BlockKind.TAB_CONTAINER
    override val children: List<BlockView> = emptyList()
}

data class TabItemView(
    override val id: String,
    val title: List<InlineView>,
    val icon: BlockIconView?,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.TAB_ITEM
}

data class TableView(
    override val id: String,
    val hasColumnHeader: Boolean,
    val hasRowHeader: Boolean,
    val rows: List<TableRowView>,
    override val style: BlockStyleView,
) : BlockView {
    override val kind = BlockKind.TABLE
    override val children: List<BlockView> = emptyList()
}

data class TableRowView(
    override val id: String,
    val cells: List<List<InlineView>>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.TABLE_ROW
}

data class MediaView
    // quality-exception: 미디어 프래그먼트의 종류·URL·파일명·캡션과 공통 블록 속성 7개를 보존하는 뷰 계약이다.
    @Suppress("LongParameterList")
    constructor(
        override val id: String,
        val type: MediaTypeView,
        val url: String?,
        val fileName: String?,
        val caption: List<InlineView>,
        override val style: BlockStyleView,
        override val children: List<BlockView>,
    ) : BlockView {
        override val kind = BlockKind.MEDIA
    }

enum class MediaTypeView { IMAGE, VIDEO, AUDIO, FILE, PDF }

data class BookmarkView(
    override val id: String,
    val url: String?,
    val caption: List<InlineView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.BOOKMARK
}

data class LinkPreviewView(
    override val id: String,
    val url: String?,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.LINK_PREVIEW
}

data class EmbedView
    // quality-exception: 임베드 프래그먼트의 제공자·주소·폴백·캡션과 공통 블록 속성 7개를 보존하는 뷰 계약이다.
    @Suppress("LongParameterList")
    constructor(
        override val id: String,
        val provider: EmbedProviderView?,
        val url: String?,
        val fallbackUrl: String?,
        val caption: List<InlineView>,
        override val style: BlockStyleView,
        override val children: List<BlockView>,
    ) : BlockView {
        override val kind = BlockKind.EMBED
    }

enum class EmbedProviderView { YOUTUBE, VIMEO }

data class ChildPostView(
    override val id: String,
    val title: String,
    val link: LinkView?,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.CHILD_POST
}

data class DocumentLinkView(
    override val id: String,
    val link: LinkView?,
    val originalUrl: String?,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.DOCUMENT_LINK
}

data class DatabaseLinkView(
    override val id: String,
    val title: String?,
    val link: LinkView?,
    val originalUrl: String?,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.DATABASE_LINK
}

data class BreadcrumbView(
    override val id: String,
    val items: List<BreadcrumbItemView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.BREADCRUMB
}

data class BreadcrumbItemView(
    val label: String,
    val link: LinkView?,
)

data class TableOfContentsView(
    override val id: String,
    val entries: List<TableOfContentsEntryView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.TABLE_OF_CONTENTS
}

data class TableOfContentsEntryView(
    val label: String,
    val href: String,
    val level: HeadingLevelView,
)

data class SynchronizedBlockView(
    override val id: String,
    val origin: SynchronizedOriginView?,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.SYNCHRONIZED
}

data class SynchronizedOriginView(
    val sourceId: String,
    val documentId: String,
    val blockId: String,
)

data class TemplateView(
    override val id: String,
    val title: List<InlineView>,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.TEMPLATE
}

data class MeetingNotesView
    // quality-exception: 회의록 프래그먼트의 제목·상태·요약·링크와 공통 블록 속성 7개를 보존하는 뷰 계약이다.
    @Suppress("LongParameterList")
    constructor(
        override val id: String,
        val title: String,
        val status: MeetingNotesStatusView,
        val summary: List<InlineView>,
        val notesLink: LinkView?,
        override val style: BlockStyleView,
        override val children: List<BlockView>,
    ) : BlockView {
        override val kind = BlockKind.MEETING_NOTES
    }

enum class MeetingNotesStatusView(
    val label: String,
) {
    NOT_STARTED("Not started"),
    IN_PROGRESS("In progress"),
    COMPLETED("Completed"),
    OTHER("Status unavailable"),
}

data class UnsupportedView(
    override val id: String,
    val originalType: String,
    override val style: BlockStyleView,
    override val children: List<BlockView>,
) : BlockView {
    override val kind = BlockKind.UNSUPPORTED
}

sealed interface BlockIconView

data class EmojiIconView(
    val value: String,
) : BlockIconView

data class MediaIconView(
    val url: String?,
) : BlockIconView

data class NativeIconView(
    val name: String,
    val colorClass: String?,
) : BlockIconView

data class CustomEmojiIconView(
    val name: String,
    val url: String?,
) : BlockIconView
