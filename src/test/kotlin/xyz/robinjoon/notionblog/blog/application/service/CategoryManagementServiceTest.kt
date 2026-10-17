package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.CategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.DuplicateCategoryNameException
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import java.util.UUID

class CategoryManagementServiceTest {
    private val categories = mockk<CategoryRepository>()
    private val newId = categoryId(9)
    private val service = CategoryManagementService(categories) { newId }
    private val kotlin = Category.restore(categoryId(1), "Kotlin", 0)
    private val spring = Category.restore(categoryId(2), "Spring", 1)

    @BeforeEach
    fun givenStoredCategories() {
        every { categories.findAll() } returns listOf(kotlin, spring)
        every { categories.assignmentCounts() } returns emptyMap()
        every { categories.save(any()) } just Runs
        every { categories.delete(any()) } just Runs
    }

    @Test
    fun `분류 목록에 배정된 글 수를 합치고 배정이 없으면 0으로 표시한다`() {
        every { categories.assignmentCounts() } returns mapOf(spring.id to 3)

        assertThat(categories.overviews()).containsExactly(
            CategoryOverview(kotlin.id, "Kotlin", 0, 0),
            CategoryOverview(spring.id, "Spring", 1, 3),
        )
    }

    @Test
    fun `a new category gets a fresh id unless another category already uses the name in any case`() {
        assertThat(service.create(" Architecture ", 2)).isEqualTo(CategoryChangeResult.Saved(newId))
        assertThat(service.create(" kOTLIN ", 2)).isEqualTo(CategoryChangeResult.NameInUse)
    }

    @Test
    fun `a category can change its own name case but not take another category's name`() {
        every { categories.lock(setOf(kotlin.id)) } returns listOf(kotlin)

        assertThat(service.update(kotlin.id, "KOTLIN", 5)).isEqualTo(CategoryChangeResult.Saved(kotlin.id))
        assertThat(service.update(kotlin.id, "spring", 5)).isEqualTo(CategoryChangeResult.NameInUse)
    }

    @Test
    fun `updating a category that no longer exists does not recreate it`() {
        every { categories.lock(setOf(newId)) } returns emptyList()

        assertThat(service.update(newId, "Architecture", 0)).isEqualTo(CategoryChangeResult.NotFound)
    }

    @Test
    fun `저장소의 이름 중복 오류를 상위로 전파한다`() {
        every { categories.save(any()) } throws DuplicateCategoryNameException("category name is already used")

        assertThatThrownBy { service.create("Architecture", 2) }.isInstanceOf(DuplicateCategoryNameException::class.java)
    }

    @Test
    fun `only categories without any assigned post can be deleted`() {
        every { categories.lock(setOf(kotlin.id)) } returns listOf(kotlin)
        every { categories.lock(setOf(spring.id)) } returns listOf(spring)
        every { categories.lock(setOf(newId)) } returns emptyList()
        every { categories.assignmentCounts() } returns mapOf(kotlin.id to 0, spring.id to 1)

        assertThat(service.delete(kotlin.id)).isEqualTo(CategoryChangeResult.Saved(kotlin.id))
        assertThat(service.delete(spring.id)).isEqualTo(CategoryChangeResult.AssignedToPosts)
        assertThat(service.delete(newId)).isEqualTo(CategoryChangeResult.NotFound)
    }

    private fun categoryId(label: Int): CategoryId = CategoryId(UUID.fromString("00000000-0000-0000-0000-%012d".format(label)))
}
