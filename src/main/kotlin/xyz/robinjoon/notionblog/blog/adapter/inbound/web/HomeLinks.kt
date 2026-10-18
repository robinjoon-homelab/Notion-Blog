package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// 공개 화면 링크를 한곳에서 만든다. 검색어는 직접 인코딩해 `+`나 `&`가 그대로 전달되게 한다.
internal object HomeLinks {
    fun home(
        page: Int,
        categoryId: CategoryId?,
        search: String?,
    ): String {
        val query =
            buildList {
                categoryId?.let { add("tag=${it.value}") }
                search?.let { add("q=${URLEncoder.encode(it, StandardCharsets.UTF_8)}") }
                if (page > 1) add("page=$page")
            }
        return if (query.isEmpty()) "/" else "/?${query.joinToString("&")}"
    }

    fun category(id: CategoryId): String = home(1, id, null)

    fun post(id: PostId): String = "/posts/${id.value}"

    fun series(id: SeriesId): String = "/series/${id.value}"
}
