package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.CategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.application.port.input.CategoryManagementUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId

// 분류 명령은 글을 게시하거나 게시 상태를 바꾸지 않는다. 이름 중복은 저장된 분류와 먼저 비교해 판정하므로 정상 경로에서 DB 제약 위반이
// 생기지 않는다. 같은 이름을 동시에 저장하면 저장소의 DuplicateCategoryNameException이 전파되어 트랜잭션 전체가 롤백된다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class CategoryManagementService(
    private val categories: CategoryRepository,
    private val categoryIdFactory: () -> CategoryId,
) : CategoryManagementUseCase {
    @Transactional(readOnly = true)
    override fun list(): List<CategoryOverview> = categories.overviews()

    @Transactional(rollbackFor = [Exception::class])
    override fun create(
        name: String,
        position: Int,
    ): CategoryChangeResult = save(Category.create(categoryIdFactory(), name, position))

    // 분류 행을 잠그므로 동시에 삭제된 분류를 다시 만들지 않는다.
    @Transactional(rollbackFor = [Exception::class])
    override fun update(
        id: CategoryId,
        name: String,
        position: Int,
    ): CategoryChangeResult {
        val category = categories.lock(setOf(id)).singleOrNull() ?: return CategoryChangeResult.NotFound
        return save(category.update(name, position))
    }

    // 분류 행을 잠근 뒤 연결 수를 센다. 이 분류를 지정하는 명령도 분류 행을 먼저 잠그므로, 삭제가 끝날 때까지 기다리거나 먼저 끝나
    // 연결 수에 잡힌다.
    @Transactional(rollbackFor = [Exception::class])
    override fun delete(id: CategoryId): CategoryChangeResult {
        if (categories.lock(setOf(id)).isEmpty()) return CategoryChangeResult.NotFound
        if (categories.assignmentCounts().getOrDefault(id, 0) > 0) return CategoryChangeResult.AssignedToPosts
        categories.delete(id)
        return CategoryChangeResult.Saved(id)
    }

    private fun save(category: Category): CategoryChangeResult {
        val nameInUse = categories.findAll().any { other -> other.id != category.id && other.normalizedName == category.normalizedName }
        if (nameInUse) return CategoryChangeResult.NameInUse
        categories.save(category)
        return CategoryChangeResult.Saved(category.id)
    }
}

// 연결 수에는 초안·보관 글도 포함되므로 관리 화면에서 삭제할 수 없는 분류를 미리 알 수 있다.
internal fun CategoryRepository.overviews(): List<CategoryOverview> {
    val counts = assignmentCounts()
    return findAll().map { category ->
        CategoryOverview(category.id, category.name, category.position, counts.getOrDefault(category.id, 0))
    }
}
