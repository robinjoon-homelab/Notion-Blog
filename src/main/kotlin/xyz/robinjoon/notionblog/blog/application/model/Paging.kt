package xyz.robinjoon.notionblog.blog.application.model

data class PageQuery(
    val number: Int,
    val size: Int,
) {
    init {
        require(number >= 1) { "page number must be at least 1" }
        require(size in 1..MAX_SIZE) { "page size must be between 1 and $MAX_SIZE" }
    }

    val offset: Long
        get() = (number - 1).toLong() * size

    // 끝을 넘은 페이지는 마지막 페이지로 바꾼다. 그래서 오래된 링크도 가장 가까운 결과를 보여 준다.
    fun clampTo(totalItems: Int): PageQuery = copy(number = number.coerceAtMost(pageCount(totalItems, size)))

    companion object {
        const val MAX_SIZE = 100

        fun pageCount(
            totalItems: Int,
            size: Int,
        ): Int = maxOf(1, (totalItems + size - 1) / size)
    }
}

data class ResultPage<T>(
    val items: List<T>,
    val number: Int,
    val size: Int,
    val totalItems: Int,
) {
    init {
        require(totalItems >= 0) { "total item count must not be negative" }
        require(items.size <= size) { "a page must not hold more items than its size" }
    }

    val totalPages: Int
        get() = PageQuery.pageCount(totalItems, size)

    val hasPrevious: Boolean
        get() = number > 1

    val hasNext: Boolean
        get() = number < totalPages

    companion object {
        // 일치하는 항목을 모두 담고 있는 메모리 목록을 페이지로 나눈다.
        fun <T> of(
            all: List<T>,
            query: PageQuery,
        ): ResultPage<T> {
            val page = query.clampTo(all.size)
            return ResultPage(all.drop(page.offset.toInt()).take(page.size), page.number, page.size, all.size)
        }
    }
}
