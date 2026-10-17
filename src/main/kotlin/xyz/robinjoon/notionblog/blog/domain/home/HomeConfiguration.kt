package xyz.robinjoon.notionblog.blog.domain.home

import xyz.robinjoon.notionblog.domain.AggregateRoot

enum class HomeLayout {
    LIST,
    THUMBNAIL_LIST,
    CARDS,
}

// 순서는 최초 게시 시각을 기준으로 하며, 같은 시각이면 글 ID로 순서를 고정한다.
enum class HomePostOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
}

data class HomePostDetails(
    val showSummary: Boolean = true,
    val showDate: Boolean = true,
    val showCategories: Boolean = true,
)

// 관리자가 고른 공개 홈 글 목록의 표시 방식이다. Notion 사이트 설정 동기화와 생명주기가 달라 앱 DB에만 따로 저장하며,
// 처음 저장하기 전에는 기존 목록과 같은 기본값을 쓴다.
@AggregateRoot
@ConsistentCopyVisibility
data class HomeConfiguration private constructor(
    val layout: HomeLayout,
    val order: HomePostOrder,
    val pageSize: Int,
    val details: HomePostDetails,
) {
    init {
        require(pageSize in MIN_PAGE_SIZE..MAX_PAGE_SIZE) { "home page size must be between $MIN_PAGE_SIZE and $MAX_PAGE_SIZE" }
    }

    companion object {
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 50
        const val DEFAULT_PAGE_SIZE = 10

        fun defaults(): HomeConfiguration =
            HomeConfiguration(HomeLayout.LIST, HomePostOrder.NEWEST_FIRST, DEFAULT_PAGE_SIZE, HomePostDetails())

        fun create(
            layout: HomeLayout,
            order: HomePostOrder,
            pageSize: Int,
            details: HomePostDetails,
        ): HomeConfiguration = HomeConfiguration(layout, order, pageSize, details)

        fun restore(
            layout: HomeLayout,
            order: HomePostOrder,
            pageSize: Int,
            details: HomePostDetails,
        ): HomeConfiguration = HomeConfiguration(layout, order, pageSize, details)
    }
}
