package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.CategoryTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostCategoryTable
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.DuplicateCategoryNameException
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId

class ExposedCategoryRepository : CategoryRepository {
    override fun findAll(): List<Category> =
        CategoryTable
            .selectAll()
            .orderBy(
                CategoryTable.position to SortOrder.ASC,
                CategoryTable.name to SortOrder.ASC,
                CategoryTable.categoryId to SortOrder.ASC,
            ).map { it.toCategory() }

    override fun find(id: CategoryId): Category? =
        CategoryTable
            .selectAll()
            .where { CategoryTable.categoryId eq id.value }
            .singleOrNull()
            ?.toCategory()

    // ID 순서로 잠가, 같은 분류를 함께 잠그는 명령끼리 서로 반대 순서로 기다리지 않게 한다.
    override fun lock(ids: Set<CategoryId>): List<Category> {
        if (ids.isEmpty()) return emptyList()

        return CategoryTable
            .selectAll()
            .where { CategoryTable.categoryId inList ids.map(CategoryId::value) }
            .orderBy(CategoryTable.categoryId to SortOrder.ASC)
            .forUpdate()
            .map { it.toCategory() }
    }

    // PostgreSQL은 실패한 문장 뒤에 트랜잭션을 쓸 수 없으므로 이름 중복은 쓰기 전에 확인하고,
    // 동시에 같은 이름이 들어온 경우만 유일성 위반으로 받아 같은 예외로 알린다.
    override fun save(category: Category) {
        if (isNameInUse(category)) throw DuplicateCategoryNameException("category name is already in use")
        try {
            CategoryTable.upsert(CategoryTable.categoryId) {
                it[categoryId] = category.id.value
                it[name] = category.name
                it[normalizedName] = category.normalizedName
                it[position] = category.position
            }
        } catch (exception: ExposedSQLException) {
            if (exception.sqlState == "23505") {
                throw DuplicateCategoryNameException("category name was used concurrently", exception)
            }
            throw exception
        }
    }

    override fun delete(id: CategoryId) {
        CategoryTable.deleteWhere { categoryId eq id.value }
    }

    // 임시 저장과 보관 글도 센다. 글이 하나도 없는 분류는 맵에 없다.
    override fun assignmentCounts(): Map<CategoryId, Int> =
        PostCategoryTable
            .select(PostCategoryTable.categoryId, PostCategoryTable.postId.count())
            .groupBy(PostCategoryTable.categoryId)
            .associate { row -> CategoryId(row[PostCategoryTable.categoryId]) to row[PostCategoryTable.postId.count()].toInt() }

    private fun isNameInUse(category: Category): Boolean =
        CategoryTable
            .select(CategoryTable.categoryId)
            .where { CategoryTable.normalizedName eq category.normalizedName }
            .any { it[CategoryTable.categoryId] != category.id.value }

    private fun ResultRow.toCategory(): Category =
        Category.restore(CategoryId(this[CategoryTable.categoryId]), this[CategoryTable.name], this[CategoryTable.position])
}
