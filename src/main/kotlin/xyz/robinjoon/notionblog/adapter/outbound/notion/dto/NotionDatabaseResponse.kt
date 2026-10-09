package xyz.robinjoon.notionblog.adapter.outbound.notion.dto

internal data class NotionDatabaseResponse(
    val id: String,
    val title: String,
    val url: String?,
    val inTrash: Boolean,
    val parent: NotionParentResponse,
    val dataSourceIds: List<String>,
)

internal data class NotionDatabaseViewResponse
    // quality-exception: Notion view 응답의 식별자, 연결 소스, 열 및 레이아웃 설정 7개를 보존한다.
    @Suppress("LongParameterList")
    constructor(
        val id: String,
        val databaseId: String,
        val name: String,
        val type: String,
        val dataSourceId: String?,
        /** Null means no property settings; an empty list explicitly exposes no columns. */
        val columns: List<NotionViewColumn>?,
        val configuration: NotionViewConfiguration? = null,
    )

internal data class NotionViewColumn(
    val propertyId: String,
    val name: String?,
    val widthPixels: Int? = null,
    val wrap: Boolean? = null,
)

internal sealed interface NotionViewConfiguration {
    data class Table(
        val wrapCells: Boolean = true,
        val frozenColumns: Int = 0,
        val showVerticalLines: Boolean = true,
    ) : NotionViewConfiguration

    data object ListView : NotionViewConfiguration

    data class Gallery(
        val cover: NotionGalleryCover? = null,
        val size: NotionGallerySize = NotionGallerySize.MEDIUM,
        val aspect: NotionGalleryAspect = NotionGalleryAspect.COVER,
        val layout: NotionGalleryLayout = NotionGalleryLayout.LIST,
    ) : NotionViewConfiguration
}

internal sealed interface NotionGalleryCover {
    data object PageCover : NotionGalleryCover

    data object PageContent : NotionGalleryCover

    data class Property(
        val propertyId: String,
    ) : NotionGalleryCover
}

internal enum class NotionGallerySize { SMALL, MEDIUM, LARGE }

internal enum class NotionGalleryAspect { CONTAIN, COVER }

internal enum class NotionGalleryLayout { LIST, COMPACT }

internal data class NotionDataSourceResponse(
    val id: String,
    val properties: List<NotionDatabaseProperty>,
    val parent: NotionParentResponse,
    val inTrash: Boolean,
)

internal data class NotionDatabaseProperty(
    val id: String,
    val name: String,
    val type: String,
)

internal data class NotionViewQueryResponse(
    val queryId: String,
    val viewId: String,
    val page: NotionPaginationResponse<String>,
)
