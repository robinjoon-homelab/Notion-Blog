package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// 공개 글과 그 게시 리비전만 읽는다. 최초 게시 시각이 최신인 순서로, 같으면 글 ID 순서로 정렬한다.
interface PublishedPostStore {
    fun find(postId: PostId): PublishedPost?

    fun findRecent(
        excludedPostIds: Set<PostId>,
        limit: Int,
    ): List<PublishedPost>

    fun findListPage(
        excludedPostIds: Set<PostId>,
        page: PageQuery,
    ): ResultPage<PostListEntry>

    fun findPublicPostIds(postIds: Set<PostId>): Set<PostId>
}
