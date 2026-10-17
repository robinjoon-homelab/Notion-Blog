package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.web.util.UriComponentsBuilder
import xyz.robinjoon.notionblog.blog.application.model.AdminPostListQuery
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import java.util.Optional

// 관리 목록의 상태 필터·제목 검색·쪽 번호. 알 수 없는 상태 값은 전체(보관 제외)로 취급한다.
internal data class AdminPostFilter(
    val status: AdminPostStatus?,
    val title: String,
    val page: Int,
) {
    fun toQuery(): AdminPostListQuery = AdminPostListQuery(status, title.trim(), PageQuery(page, PAGE_SIZE))

    fun withStatus(value: AdminPostStatus?): AdminPostFilter = copy(status = value, page = 1)

    fun pageHref(target: Int): String =
        UriComponentsBuilder
            .fromPath("/admin/posts")
            .queryParamIfPresent("status", Optional.ofNullable(status?.name?.lowercase()))
            .queryParamIfPresent("q", Optional.ofNullable(title.takeIf(String::isNotBlank)))
            .queryParamIfPresent("page", Optional.ofNullable(target.takeIf { it > 1 }))
            .build()
            .encode()
            .toUriString()

    companion object {
        const val PAGE_SIZE = 20

        fun of(
            status: String?,
            title: String?,
            page: Int,
        ): AdminPostFilter =
            AdminPostFilter(
                AdminPostStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) },
                title.orEmpty(),
                page.coerceAtLeast(1),
            )
    }
}
