package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.CategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId

// 분류를 만들고 고치고 지운다. 어느 명령도 글을 게시하거나 게시 상태를 바꾸지 않는다. 이름과 순서가 규칙에 맞지 않으면
// IllegalArgumentException을 던지므로 호출자가 먼저 검증한다.
interface CategoryManagementUseCase {
    fun list(): List<CategoryOverview>

    fun create(
        name: String,
        position: Int,
    ): CategoryChangeResult

    fun update(
        id: CategoryId,
        name: String,
        position: Int,
    ): CategoryChangeResult

    fun delete(id: CategoryId): CategoryChangeResult
}
