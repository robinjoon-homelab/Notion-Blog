package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicCategoryCount
import xyz.robinjoon.notionblog.blog.application.model.PublishedListQuery
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// 공개 글과 그 게시 리비전만 읽는다. 같은 게시 시각은 글 ID 순서로 정렬한다.
interface PublishedPostStore {
    fun find(postId: PostId): PublishedPost?

    fun findRecent(
        excludedPostIds: Set<PostId>,
        limit: Int,
    ): List<PublishedPost>

    fun findListPage(
        excludedPostIds: Set<PostId>,
        query: PublishedListQuery,
    ): ResultPage<PostListEntry>

    fun findCategoryCounts(excludedPostIds: Set<PostId>): List<PublicCategoryCount>

    // 태그·검색과 무관한 공개 글 수다.
    fun countListed(excludedPostIds: Set<PostId>): Int

    // 공개 글에 배정된 태그를 표시 순서, 이름, ID 순으로 반환한다. 공개 글이 아니면 빈 목록이다.
    fun findCategories(postId: PostId): List<ListedCategory>

    fun findPublicPostIds(postIds: Set<PostId>): Set<PostId>
}
