package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId

// 관리자가 만든 분류다. 정규화한 이름의 유일성과 글이 연결된 분류의 삭제 금지는 DB 제약으로도 보장한다.
interface CategoryRepository {
    // 표시 순서, 이름, ID 순이다.
    fun findAll(): List<Category>

    fun find(id: CategoryId): Category?

    // 있는 분류 행만 ID 순서로 잠가 반환하므로 일부가 없으면 목록이 짧아진다. 모든 명령이 같은 순서로 잠가 교착을 피한다.
    fun lock(ids: Set<CategoryId>): List<Category>

    // 없으면 추가하고 있으면 이름과 순서를 바꾼다. 정규화한 이름이 다른 분류와 겹치면 DuplicateCategoryNameException을 던진다.
    fun save(category: Category)

    fun delete(id: CategoryId)

    // 초안·보관 글을 포함해 분류마다 연결된 글 수다. 연결된 글이 없는 분류는 빠질 수 있다.
    fun assignmentCounts(): Map<CategoryId, Int>
}

class DuplicateCategoryNameException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
