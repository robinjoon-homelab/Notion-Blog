package xyz.robinjoon.notionblog.adapter.inbound.web.view

import kotlin.math.roundToInt

data class DataTableView
    // quality-exception: 데이터 표 프래그먼트의 열·행·제목 열·고정 열·구분선 등 8개 필드를 보존하는 뷰 계약이다.
    @Suppress("LongParameterList")
    constructor(
        override val id: String,
        val title: String,
        val columns: List<DataTableColumnView>,
        val rows: List<DataTableRowView>,
        val titleColumnIndex: Int?,
        val frozenColumns: Int,
        val showVerticalLines: Boolean,
        override val style: BlockStyleView,
    ) : BlockView {
        override val kind = BlockKind.DATA_TABLE
        override val children: List<BlockView> = emptyList()
    }

data class DataTableColumnView(
    val name: String,
    val widthClass: DataColumnWidthClass,
    val wrap: Boolean,
    val frozen: Boolean,
) {
    val classes: List<String> =
        buildList {
            add(widthClass.cssClass)
            add(if (wrap) "notion-data-wrap" else "notion-data-nowrap")
            if (frozen) add("notion-data-frozen")
        }
}

enum class DataColumnWidthClass(
    val cssClass: String,
) {
    PIXELS_80("notion-data-width-80"),
    PIXELS_120("notion-data-width-120"),
    PIXELS_160("notion-data-width-160"),
    PIXELS_200("notion-data-width-200"),
    PIXELS_240("notion-data-width-240"),
    PIXELS_280("notion-data-width-280"),
    PIXELS_320("notion-data-width-320"),
    PIXELS_360("notion-data-width-360"),
    PIXELS_400("notion-data-width-400"),
    PIXELS_440("notion-data-width-440"),
    PIXELS_480("notion-data-width-480"),
    PIXELS_520("notion-data-width-520"),
    PIXELS_560("notion-data-width-560"),
    PIXELS_600("notion-data-width-600"),
    PIXELS_640("notion-data-width-640"),
    ;

    companion object {
        fun fromPixels(pixels: Int?): DataColumnWidthClass =
            if (pixels == null ||
                pixels == 0
            ) {
                PIXELS_200
            } else {
                entries[(pixels.coerceIn(80, 640) / 40.0).roundToInt() - 2]
            }
    }
}

data class DataTableRowView(
    val cells: List<List<InlineView>>,
    val icon: BlockIconView?,
)

data class DataListView(
    override val id: String,
    val title: String,
    val rows: List<DataEntryView>,
    override val style: BlockStyleView,
) : BlockView {
    override val kind = BlockKind.DATA_LIST
    override val children: List<BlockView> = emptyList()
}

data class DataGalleryView(
    override val id: String,
    val title: String,
    val rows: List<DataEntryView>,
    val classes: List<String>,
    override val style: BlockStyleView,
) : BlockView {
    override val kind = BlockKind.DATA_GALLERY
    override val children: List<BlockView> = emptyList()
}

data class DataEntryView(
    val title: List<InlineView>,
    val titleText: String,
    val link: LinkView?,
    val icon: BlockIconView?,
    val coverUrl: String?,
    val properties: List<DataPropertyView>,
)

data class DataPropertyView(
    val name: String,
    val content: List<InlineView>,
)
