package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.AdminPostListQuery
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// 화면의 공개·비공개·보관 묶음이다. 저장된 상태는 그대로이고 어떤 상태들을 한 묶음으로 보이는지만 정한다.
internal enum class AdminPostVisibility(
    val param: String,
    val label: String,
    val statuses: Set<AdminPostStatus>,
    private val legacyParams: Set<String> = emptySet(),
) {
    PUBLIC("public", "공개", setOf(AdminPostStatus.PUBLISHED, AdminPostStatus.CHANGES_PENDING), setOf("published", "changes_pending")),
    PRIVATE("private", "비공개", setOf(AdminPostStatus.DRAFT, AdminPostStatus.UNPUBLISHED), setOf("draft", "unpublished")),
    ARCHIVED("archived", "보관", setOf(AdminPostStatus.ARCHIVED)),
    ;

    companion object {
        // 옛 주소의 상태 이름도 같은 묶음으로 읽어 북마크를 유지한다. 알 수 없는 값은 전체로 취급한다.
        fun of(value: String?): AdminPostVisibility? =
            entries.firstOrNull { visibility ->
                value.equals(visibility.param, ignoreCase = true) || visibility.legacyParams.any { value.equals(it, ignoreCase = true) }
            }
    }
}

// 관리 목록의 공개 묶음 필터·제목 검색·쪽 번호. 필터가 없으면 보관한 글을 뺀 모든 글이다.
internal data class AdminPostFilter(
    val visibility: AdminPostVisibility?,
    val title: String,
    val page: Int,
) {
    fun toQuery(): AdminPostListQuery = AdminPostListQuery(visibility?.statuses.orEmpty(), title.trim(), PageQuery(page, PAGE_SIZE))

    fun withVisibility(value: AdminPostVisibility?): AdminPostFilter = copy(visibility = value, page = 1)

    fun pageHref(target: Int): String {
        val query =
            buildList {
                visibility?.let { add("status=${it.param}") }
                title.takeIf(String::isNotBlank)?.let { add("q=${URLEncoder.encode(it, StandardCharsets.UTF_8)}") }
                if (target > 1) add("page=$target")
            }
        return if (query.isEmpty()) "/admin/posts" else "/admin/posts?${query.joinToString("&")}"
    }

    companion object {
        const val PAGE_SIZE = 20

        fun of(
            status: String?,
            title: String?,
            page: Int,
        ): AdminPostFilter = AdminPostFilter(AdminPostVisibility.of(status), title.orEmpty(), page.coerceAtLeast(1))
    }
}
