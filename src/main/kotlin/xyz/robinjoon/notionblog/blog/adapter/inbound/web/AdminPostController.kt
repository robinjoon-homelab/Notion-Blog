package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
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
        val previewVersion = previewVersion(version)
        return when (val result = queries.get(id, previewVersion)) {
            is AdminPostLookupResult.Found -> {
                val view = assembler.preview(result.preview, result.classification, previewVersion, model.notice())
                ModelAndView("admin/preview").addObject("view", view)
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

    // 발행 확인. 가져온 내용은 읽기 전용으로 보이고, 공개 중인 내용은 같은 화면에서 비교만 한다.
    // 발행할 내용이 없어도 비교 화면은 열어 두고 사유만 화면이 알린다. 가져온 내용이 아예 없을 때만 상세로 돌려보낸다.
    @GetMapping("/admin/posts/{postId}/review")
    fun review(
        @PathVariable postId: String,
        @RequestParam(defaultValue = "latest") version: String,
        model: Model,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
        val previewVersion = previewVersion(version)
        return when (val result = queries.get(id, previewVersion)) {
            is AdminPostLookupResult.Found -> {
                val view = assembler.preview(result.preview, result.classification, previewVersion, model.notice())
                ModelAndView("admin/review").addObject("view", view)
            }

            is AdminPostLookupResult.NotImported -> {
                redirect(postPath(id), attributes, "아직 가져온 내용이 없습니다. 먼저 Notion에서 가져오세요.", true)
            }

            AdminPostLookupResult.NotFound -> {
                errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
            }

            AdminPostLookupResult.ContentUnavailable -> {
                errorView("error/blog-unavailable", HttpStatus.SERVICE_UNAVAILABLE)
            }
        }
    }

    private fun previewVersion(version: String): PreviewVersion =
        if (version.equals("published", ignoreCase = true)) PreviewVersion.PUBLISHED else PreviewVersion.LATEST
}
