package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.category

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedCategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.DuplicateCategoryNameException
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows
import java.util.UUID

class ExposedCategoryRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedCategoryRepository()
    private val first = categoryId(1)
    private val second = categoryId(2)
    private val third = categoryId(3)

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `categories are listed by position then name then id and can be renamed in place`() {
        database.inTransaction {
            repository.save(Category.create(third, "Kotlin", 1))
            repository.save(Category.create(second, "Android", 1))
            repository.save(Category.create(first, "Essay", 0))

            assertThat(repository.findAll().map { it.id }).containsExactly(first, second, third)

            repository.save(Category.create(first, "Notes", 5))

            assertThat(repository.find(first)).isEqualTo(Category.restore(first, "Notes", 5))
            assertThat(repository.findAll().map { it.id }).containsExactly(second, third, first)
            assertThat(repository.find(categoryId(9))).isNull()
        }
    }

    @Test
    fun `names that differ only by letter case are duplicates but a category may keep its own name`() {
        database.inTransaction {
            repository.save(Category.create(first, "Kotlin", 0))

            assertThatThrownBy { repository.save(Category.create(second, "KOTLIN", 1)) }
                .isInstanceOf(DuplicateCategoryNameException::class.java)
            repository.save(Category.create(first, "kotlin", 2))

            assertThat(repository.findAll()).containsExactly(Category.restore(first, "kotlin", 2))
        }
    }

    @Test
    fun `locking returns only the requested categories in id order`() {
        database.inTransaction {
            repository.save(Category.create(third, "C", 0))
            repository.save(Category.create(first, "A", 2))
            repository.save(Category.create(second, "B", 1))

            assertThat(repository.lock(setOf(third, first, categoryId(9))).map { it.id }).containsExactly(first, third)
            assertThat(repository.lock(emptySet())).isEmpty()
        }
    }

    @Test
    fun `assignment counts include every post and a category in use cannot be deleted`() {
        database.inTransaction {
            repository.save(Category.create(first, "Used", 0))
            repository.save(Category.create(second, "Unused", 1))
            (1..2).forEach { PostRows.post(PostFixtures.postId(it), PostFixtures.document("doc-$it")) }
            PostRows.assignment(PostFixtures.postId(1), first)
            PostRows.assignment(PostFixtures.postId(2), first)

            assertThat(repository.assignmentCounts()).containsExactlyEntriesOf(mapOf(first to 2))
            assertThatThrownBy { repository.delete(first) }.isInstanceOf(ExposedSQLException::class.java)

            repository.delete(second)

            assertThat(repository.findAll().map { it.id }).containsExactly(first)
        }
    }

    private fun categoryId(label: Int) = CategoryId(UUID.fromString("00000000-0000-0000-0000-%012d".format(label)))
}
