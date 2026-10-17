package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPostCategoriesView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.CategoryOptionView
import xyz.robinjoon.notionblog.blog.application.model.PostCategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostCategoryLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostCategorySelection
import xyz.robinjoon.notionblog.blog.application.port.input.PostCategoriesUseCase

// 글 하나의 카테고리 지정. 선택한 목록 전체로 교체하며 빈 목록은 모든 지정을 해제한다.
@Controller
internal class AdminPostCategoryController(
    private val postCategories: PostCategoriesUseCase,
) {
    @GetMapping("/admin/posts/{postId}/categories")
    fun edit(
        @PathVariable postId: String,
        model: Model,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        return when (val result = postCategories.get(id)) {
            is PostCategoryLookupResult.Found -> ModelAndView("admin/post-categories").addObject("view", view(result.selection, model))
            PostCategoryLookupResult.NotFound -> notFound()
        }
    }

    @PostMapping("/admin/posts/{postId}/categories")
    fun save(
        @PathVariable postId: String,
        @RequestParam(name = "categoryId", required = false) categoryIds: List<String>?,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        val selected =
            categoryIds.orEmpty().map { WebIds.categoryId(it) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST) }.toSet()
        val path = "${postPath(id)}/categories"
        return when (postCategories.assign(id, selected)) {
            PostCategoryChangeResult.Saved -> redirect(path, attributes, "카테고리 지정을 저장했습니다. 공개 여부는 바뀌지 않습니다.")
            PostCategoryChangeResult.CategoryNotFound -> redirect(path, attributes, "삭제되었거나 없는 카테고리가 포함되어 있습니다. 다시 선택하세요.", true)
            PostCategoryChangeResult.PostNotFound -> notFound()
        }
    }

    private fun view(
        selection: PostCategorySelection,
        model: Model,
    ): AdminPostCategoriesView =
        AdminPostCategoriesView(
            selection.postId.value.toString(),
            selection.title,
            selection.categories.map { CategoryOptionView(it.id.value.toString(), it.name, it.assignedPosts, it.id in selection.selected) },
            model.notice(),
        )

    private fun notFound(): ModelAndView = errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
}
