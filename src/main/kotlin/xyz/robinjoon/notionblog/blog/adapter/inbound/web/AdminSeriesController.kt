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
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminSeriesEditView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminSeriesListView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminSeriesRowView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.BadgeView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesMemberView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.SeriesChangeResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesEditor
import xyz.robinjoon.notionblog.blog.application.model.SeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostSummary
import xyz.robinjoon.notionblog.blog.application.port.input.SeriesManagementUseCase
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 시리즈 생성·저장(정보와 소속 글 순서)·삭제. 저장은 한 번에 원자적으로 반영한다.
@Controller
internal class AdminSeriesController(
    private val series: SeriesManagementUseCase,
) {
    @GetMapping("/admin/series")
    fun list(model: Model): ModelAndView =
        ModelAndView("admin/series").addObject("view", AdminSeriesListView(series.list().map(::row), model.notice()))

    @PostMapping("/admin/series")
    fun create(
        form: AdminSeriesForm,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val input = form.toInput() ?: return redirect(LIST_PATH, attributes, INVALID_INPUT, true)
        return when (val result = series.create(input.title, input.description)) {
            is SeriesChangeResult.Saved -> redirect(editPath(result.id), attributes, "시리즈를 만들었습니다. 글을 추가하고 저장하세요.")
            SeriesChangeResult.NotFound, SeriesChangeResult.PostNotFound -> redirect(LIST_PATH, attributes, INVALID_INPUT, true)
        }
    }

    @GetMapping("/admin/series/{seriesId}")
    fun edit(
        @PathVariable seriesId: String,
        model: Model,
    ): ModelAndView {
        val id = WebIds.seriesId(seriesId) ?: return notFound()
        return when (val result = series.get(id)) {
            is SeriesLookupResult.Found -> ModelAndView("admin/series-edit").addObject("view", editView(result.editor, model))
            SeriesLookupResult.NotFound -> notFound()
        }
    }

    // 제목·설명과 소속 글 전체(읽는 순서)를 한 번에 저장한다. 빠진 글은 시리즈에서만 빠지고 다른 시리즈의 글은 이 시리즈로 옮겨 온다.
    @PostMapping("/admin/series/{seriesId}")
    fun save(
        @PathVariable seriesId: String,
        form: AdminSeriesForm,
        @RequestParam(name = "postId", required = false) postIds: List<String>?,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.seriesId(seriesId) ?: return notFound()
        val members = postIds.orEmpty().map { WebIds.postId(it) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST) }.distinct()
        val path = editPath(id)
        val input = form.toInput() ?: return redirect(path, attributes, INVALID_INPUT, true)
        return when (series.save(id, input.title, input.description, members)) {
            is SeriesChangeResult.Saved -> redirect(path, attributes, "시리즈를 저장했습니다. 공개 글이 있는 시리즈는 내 블로그에 바로 반영됩니다.")
            SeriesChangeResult.NotFound -> notFound()
            SeriesChangeResult.PostNotFound -> redirect(path, attributes, "없거나 관리할 수 없는 글이 포함되어 있어 저장하지 않았습니다. 화면을 새로 고쳐 다시 확인하세요.", true)
        }
    }

    @PostMapping("/admin/series/{seriesId}/delete")
    fun delete(
        @PathVariable seriesId: String,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.seriesId(seriesId) ?: return notFound()
        return when (series.delete(id)) {
            is SeriesChangeResult.Saved -> redirect(LIST_PATH, attributes, "시리즈를 삭제했습니다. 글과 태그, 공개 상태는 그대로입니다.")
            SeriesChangeResult.NotFound -> notFound()
            SeriesChangeResult.PostNotFound -> redirect(editPath(id), attributes, INVALID_INPUT, true)
        }
    }

    private fun row(overview: SeriesOverview): AdminSeriesRowView =
        AdminSeriesRowView(overview.id.value.toString(), overview.title, overview.description, overview.postCount, overview.publicPostCount)

    private fun editView(
        editor: SeriesEditor,
        model: Model,
    ): AdminSeriesEditView =
        AdminSeriesEditView(
            editor.overview.id.value
                .toString(),
            editor.overview.title,
            editor.overview.description,
            editor.members.map(::member),
            editor.candidates.map(::member),
            model.notice(),
        )

    private fun member(post: SeriesPostSummary): SeriesMemberView =
        SeriesMemberView(post.postId.value.toString(), post.title, listOf(visibilityBadge(post.status)), post.currentSeriesTitle)

    private fun visibilityBadge(status: AdminPostStatus): BadgeView =
        when (status) {
            AdminPostStatus.PUBLISHED, AdminPostStatus.CHANGES_PENDING -> BadgeView("공개", "live")
            AdminPostStatus.DRAFT, AdminPostStatus.UNPUBLISHED -> BadgeView("비공개", "draft")
            AdminPostStatus.ARCHIVED -> BadgeView("보관", "draft")
        }

    private fun editPath(id: SeriesId): String = "/admin/series/${id.value}"

    private fun notFound(): ModelAndView = errorView("error/blog-not-found", HttpStatus.NOT_FOUND)

    private companion object {
        const val LIST_PATH = "/admin/series"
        const val INVALID_INPUT = "제목은 1~${Series.MAX_TITLE_LENGTH}자, 설명은 ${Series.MAX_DESCRIPTION_LENGTH}자 이하로 입력하세요."
    }
}

internal data class AdminSeriesForm(
    val title: String = "",
    val description: String = "",
) {
    // 도메인의 제목·설명 규칙을 화면 입력 단계에서 먼저 확인해 오류를 알림으로 돌려준다.
    fun toInput(): SeriesInput? {
        val trimmedTitle = title.trim()
        val trimmedDescription = description.trim()
        val valid = trimmedTitle.length in 1..Series.MAX_TITLE_LENGTH && trimmedDescription.length <= Series.MAX_DESCRIPTION_LENGTH
        return if (valid) SeriesInput(trimmedTitle, trimmedDescription) else null
    }
}

internal data class SeriesInput(
    val title: String,
    val description: String,
)
