package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminCategoriesView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminCategoryRowView
import xyz.robinjoon.notionblog.blog.application.model.CategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.CategoryOverview
import xyz.robinjoon.notionblog.blog.application.port.input.CategoryManagementUseCase
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId

// 카테고리 생성·이름과 표시 순서 변경·삭제. 글이 지정된 카테고리의 삭제 거절은 입력 포트의 결과를 그대로 따른다.
@Controller
internal class AdminCategoryController(
    private val categories: CategoryManagementUseCase,
) {
    @GetMapping("/admin/categories")
    fun list(model: Model): ModelAndView =
        ModelAndView("admin/categories")
            .addObject("view", AdminCategoriesView(categories.list().map(::row), model.notice()))

    @PostMapping("/admin/categories")
    fun create(
        form: AdminCategoryForm,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val input = form.toInput() ?: return redirect(CATEGORIES_PATH, attributes, INVALID_INPUT, true)
        return when (categories.create(input.name, input.position)) {
            is CategoryChangeResult.Saved -> redirect(CATEGORIES_PATH, attributes, "카테고리를 추가했습니다. 글에는 아직 지정되지 않았습니다.")
            else -> redirect(CATEGORIES_PATH, attributes, NAME_IN_USE, true)
        }
    }

    @PostMapping("/admin/categories/{categoryId}")
    fun update(
        @PathVariable categoryId: String,
        form: AdminCategoryForm,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.categoryId(categoryId) ?: return notFound()
        val input = form.toInput() ?: return redirect(CATEGORIES_PATH, attributes, INVALID_INPUT, true)
        return when (categories.update(id, input.name, input.position)) {
            is CategoryChangeResult.Saved -> redirect(CATEGORIES_PATH, attributes, "카테고리를 저장했습니다.")
            CategoryChangeResult.NameInUse -> redirect(CATEGORIES_PATH, attributes, NAME_IN_USE, true)
            CategoryChangeResult.NotFound -> notFound()
            CategoryChangeResult.AssignedToPosts -> redirect(CATEGORIES_PATH, attributes, NAME_IN_USE, true)
        }
    }

    @PostMapping("/admin/categories/{categoryId}/delete")
    fun delete(
        @PathVariable categoryId: String,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id: CategoryId = WebIds.categoryId(categoryId) ?: return notFound()
        return when (categories.delete(id)) {
            is CategoryChangeResult.Saved -> redirect(CATEGORIES_PATH, attributes, "카테고리를 삭제했습니다.")
            CategoryChangeResult.AssignedToPosts -> redirect(CATEGORIES_PATH, attributes, "글이 지정된 카테고리는 삭제할 수 없습니다. 먼저 글의 지정을 해제하세요.", true)
            CategoryChangeResult.NotFound -> notFound()
            CategoryChangeResult.NameInUse -> redirect(CATEGORIES_PATH, attributes, NAME_IN_USE, true)
        }
    }

    private fun row(overview: CategoryOverview): AdminCategoryRowView =
        AdminCategoryRowView(
            overview.id.value.toString(),
            overview.name,
            overview.position,
            overview.assignedPosts,
            overview.assignedPosts == 0,
        )

    private fun notFound(): ModelAndView = errorView("error/blog-not-found", HttpStatus.NOT_FOUND)

    private companion object {
        const val CATEGORIES_PATH = "/admin/categories"
        const val INVALID_INPUT = "이름은 1~50자로, 표시 순서는 0 이상의 정수로 입력하세요."
        const val NAME_IN_USE = "같은 이름의 카테고리가 이미 있습니다. 대소문자는 구분하지 않습니다."
    }
}

internal data class AdminCategoryForm(
    val name: String = "",
    val position: String = "0",
) {
    // 도메인의 이름·순서 규칙을 화면 입력 단계에서 먼저 확인해 오류를 알림으로 돌려준다.
    fun toInput(): CategoryInput? {
        val trimmed = name.trim()
        val order = position.trim().toIntOrNull()?.takeIf { it >= 0 }
        return if (trimmed.length in nameLength && order != null) CategoryInput(trimmed, order) else null
    }

    private companion object {
        val nameLength = 1..50
    }
}

internal data class CategoryInput(
    val name: String,
    val position: Int,
)
