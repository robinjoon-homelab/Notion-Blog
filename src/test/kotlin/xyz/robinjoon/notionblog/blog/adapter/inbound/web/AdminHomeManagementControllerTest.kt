package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.model.CategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.port.input.CategoryManagementUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.HomeConfigurationUseCase
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import java.util.UUID

class AdminHomeManagementControllerTest {
    private val homeConfigurations = mockk<HomeConfigurationUseCase>()
    private val categories = mockk<CategoryManagementUseCase>()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AdminHomeController(homeConfigurations),
                AdminCategoryController(categories),
            ).build()
    private val categoryId = CategoryId(UUID.nameUUIDFromBytes("category".toByteArray()))

    @Test
    fun `home settings are saved from the submitted form and unchecked boxes turn details off`() {
        val expected =
            HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.OLDEST_FIRST, 7, HomePostDetails(false, true, false))
        every { homeConfigurations.update(expected) } returns Unit

        val result =
            mockMvc
                .perform(
                    post(
                        "/admin/home",
                    ).param("layout", "CARDS").param("order", "OLDEST_FIRST").param("pageSize", "7").param("showDate", "true"),
                ).andExpect(status().isSeeOther)
                .andExpect(redirectedUrl("/admin/home"))
                .andReturn()

        assertThat(result.notice().error).isFalse()
        assertThat(result.notice().text).isEqualTo("설정을 저장했습니다. 내 블로그에 바로 반영됩니다.")
    }

    @Test
    fun `home form binds checked boxes and falls back to false when they are absent`() {
        val allChecked =
            HomeConfiguration.create(
                HomeLayout.THUMBNAIL_LIST,
                HomePostOrder.NEWEST_FIRST,
                5,
                HomePostDetails(true, true, true),
            )
        val noneChecked = HomeConfiguration.create(HomeLayout.LIST, HomePostOrder.NEWEST_FIRST, 5, HomePostDetails(false, false, false))
        every { homeConfigurations.update(allChecked) } returns Unit
        every { homeConfigurations.update(noneChecked) } returns Unit

        val checked =
            mockMvc
                .perform(
                    post("/admin/home")
                        .param("layout", "THUMBNAIL_LIST")
                        .param("order", "NEWEST_FIRST")
                        .param("pageSize", "5")
                        .param("showSummary", "true")
                        .param("showDate", "true")
                        .param("showCategories", "true"),
                ).andReturn()
        val absent =
            mockMvc.perform(post("/admin/home").param("layout", "LIST").param("order", "NEWEST_FIRST").param("pageSize", "5")).andReturn()

        assertThat(checked.notice().error).isFalse()
        assertThat(absent.notice().error).isFalse()
    }

    @Test
    fun `home settings outside the allowed values are rejected without saving`() {
        listOf("0", "51", "ten").forEach { size ->
            val result =
                mockMvc
                    .perform(post("/admin/home").param("layout", "LIST").param("order", "NEWEST_FIRST").param("pageSize", size))
                    .andExpect(redirectedUrl("/admin/home"))
                    .andReturn()
            assertThat(result.notice().error).isTrue()
        }
        val unknownLayout =
            mockMvc
                .perform(
                    post("/admin/home").param("layout", "GRID").param("order", "NEWEST_FIRST").param("pageSize", "10"),
                ).andReturn()
        assertThat(unknownLayout.notice().error).isTrue()
    }

    @Test
    fun `category create trims the name and reports duplicates`() {
        every { categories.create("Kotlin", 2) } returns CategoryChangeResult.Saved(categoryId)
        assertThat(categoryNotice("/admin/categories", " Kotlin ", "2").error).isFalse()

        every { categories.create("Kotlin", 3) } returns CategoryChangeResult.NameInUse
        assertThat(categoryNotice("/admin/categories", "Kotlin", "3").text).contains("같은 이름의 태그가 이미 있습니다")
    }

    @Test
    fun `category input that breaks the name or order rules is rejected`() {
        assertThat(categoryNotice("/admin/categories", "   ", "0").error).isTrue()
        assertThat(categoryNotice("/admin/categories", "a".repeat(51), "0").error).isTrue()
        assertThat(categoryNotice("/admin/categories", "Kotlin", "-1").error).isTrue()
        assertThat(categoryNotice("/admin/categories", "Kotlin", "one").error).isTrue()
    }

    @Test
    fun `category update and delete report the use case outcome`() {
        val path = "/admin/categories/${categoryId.value}"
        every { categories.update(categoryId, "Spring", 1) } returns CategoryChangeResult.Saved(categoryId)
        assertThat(categoryNotice(path, "Spring", "1").error).isFalse()

        every { categories.delete(categoryId) } returns CategoryChangeResult.AssignedToPosts
        assertThat(categoryNotice("$path/delete", null, null).text).contains("비공개·보관 글을 포함해", "삭제할 수 없습니다")

        every { categories.delete(categoryId) } returns CategoryChangeResult.Saved(categoryId)
        assertThat(categoryNotice("$path/delete", null, null).error).isFalse()

        every { categories.update(categoryId, "Spring", 1) } returns CategoryChangeResult.NotFound
        mockMvc.perform(post(path).param("name", "Spring").param("position", "1")).andExpect(status().isNotFound)
        mockMvc.perform(post("/admin/categories/nope/delete")).andExpect(status().isNotFound)
    }

    private fun categoryNotice(
        path: String,
        name: String?,
        position: String?,
    ): NoticeView {
        val request = post(path)
        if (name != null) request.param("name", name)
        if (position != null) request.param("position", position)
        return mockMvc
            .perform(request)
            .andExpect(redirectedUrl("/admin/categories"))
            .andReturn()
            .notice()
    }

    private fun MvcResult.notice(): NoticeView = checkNotNull(flashMap["notice"]) as NoticeView
}
