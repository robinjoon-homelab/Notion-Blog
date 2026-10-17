package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.application.port.input.AdminPostQueryUseCase

// 관리 화면. 인증·CSRF는 보안 필터가 처리하고, 여기서는 입력 포트 호출과 화면 전환만 한다.
@Controller
internal class AdminPostController(
    private val queries: AdminPostQueryUseCase,
    private val assembler: AdminViewAssembler,
) {
    @GetMapping("/admin/posts")
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "1") page: Int,
        model: Model,
    ): ModelAndView =
        AdminPostFilter.of(status, q, page).let { filter ->
            ModelAndView("admin/posts").addObject("page", assembler.list(queries.list(filter.toQuery()), filter, model.notice()))
        }

    @GetMapping("/admin/posts/{postId}")
    fun detail(
        @PathVariable postId: String,
        @RequestParam(defaultValue = "latest") version: String,
        model: Model,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
        val previewVersion = if (version.equals("published", ignoreCase = true)) PreviewVersion.PUBLISHED else PreviewVersion.LATEST
        return when (val result = queries.get(id, previewVersion)) {
            is AdminPostLookupResult.Found -> {
                ModelAndView("admin/preview").addObject("view", assembler.preview(result.preview, previewVersion, model.notice()))
            }

            is AdminPostLookupResult.NotImported -> {
                ModelAndView("admin/pending").addObject("view", assembler.pendingImport(result.summary, result.actions, model.notice()))
            }

            AdminPostLookupResult.NotFound -> {
                errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
            }

            AdminPostLookupResult.ContentUnavailable -> {
                errorView("error/blog-unavailable", HttpStatus.SERVICE_UNAVAILABLE)
            }
        }
    }
}
