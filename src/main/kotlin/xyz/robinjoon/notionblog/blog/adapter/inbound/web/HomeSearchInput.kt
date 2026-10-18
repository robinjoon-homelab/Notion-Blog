package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.HomePageQuery

// 입력한 검색어를 보존하고 포트가 받는 형태(앞뒤 공백 없음, 빈 값 없음, 길이 제한)만 query로 낸다.
// 길이를 넘으면 자르지 않고 검색을 적용하지 않은 채 화면에 이유를 보여 준다.
internal data class HomeSearchInput(
    val text: String,
) {
    private val trimmed = text.trim()
    val tooLong: Boolean = trimmed.length > HomePageQuery.MAX_SEARCH_LENGTH
    val query: String? = trimmed.takeIf { it.isNotEmpty() && !tooLong }
}
