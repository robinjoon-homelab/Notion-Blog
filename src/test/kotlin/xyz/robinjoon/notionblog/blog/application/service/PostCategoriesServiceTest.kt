package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.application.model.PostCategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostCategoryLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostCategorySelection
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.util.UUID

class PostCategoriesServiceTest {
    private val posts = mockk<PostRepository>()
    private val categories = mockk<CategoryRepository>()
    private val service = PostCategoriesService(posts, categories)
    private val postId = PostFixtures.postId(1)
    private val kotlin = Category.restore(categoryId(1), "Kotlin", 0)
    private val spring = Category.restore(categoryId(2), "Spring", 1)
    private val draft = PostFixtures.privatePost(postId, latest = PostFixtures.revisionId(1), generation = 2)

    @BeforeEach
    fun givenStoredState() {
        every { posts.find(postId) } returns draft.categorize(setOf(spring.id))
        every { posts.lock(postId) } returns draft
        every { posts.findTitles(setOf(postId)) } returns mapOf(postId to "Draft title")
        every { posts.saveCategories(any()) } just Runs
        every { categories.findAll() } returns listOf(kotlin, spring)
        every { categories.assignmentCounts() } returns mapOf(spring.id to 4)
    }

    @Test
    fun `the selection shows every category with counts and the post's current choice`() {
        val expected =
            PostCategorySelection(
                postId = postId,
                title = "Draft title",
                categories = listOf(CategoryOverview(kotlin.id, "Kotlin", 0, 0), CategoryOverview(spring.id, "Spring", 1, 4)),
                selected = setOf(spring.id),
            )

        assertThat(service.get(postId)).isEqualTo(PostCategoryLookupResult.Found(expected))
    }

    @Test
    fun `an unmanaged post has no category selection`() {
        val unknown = PostFixtures.postId(2)
        every { posts.find(unknown) } returns null

        assertThat(service.get(unknown)).isEqualTo(PostCategoryLookupResult.NotFound)
    }

    @Test
    fun `a draft post can take several categories or have all of them cleared`() {
        every { categories.lock(setOf(kotlin.id, spring.id)) } returns listOf(kotlin, spring)
        every { categories.lock(emptySet()) } returns emptyList()

        assertThat(service.assign(postId, setOf(kotlin.id, spring.id))).isEqualTo(PostCategoryChangeResult.Saved)
        assertThat(service.assign(postId, emptySet())).isEqualTo(PostCategoryChangeResult.Saved)
    }

    @Test
    fun `assignments naming a deleted category or an unknown post are rejected`() {
        val deleted = categoryId(3)
        val unknown = PostFixtures.postId(2)
        every { categories.lock(setOf(kotlin.id, deleted)) } returns listOf(kotlin)
        every { categories.lock(setOf(kotlin.id)) } returns listOf(kotlin)
        every { posts.lock(unknown) } returns null

        assertThat(service.assign(postId, setOf(kotlin.id, deleted))).isEqualTo(PostCategoryChangeResult.CategoryNotFound)
        assertThat(service.assign(unknown, setOf(kotlin.id))).isEqualTo(PostCategoryChangeResult.PostNotFound)
    }

    private fun categoryId(label: Int): CategoryId = CategoryId(UUID.fromString("00000000-0000-0000-0000-%012d".format(label)))
}
