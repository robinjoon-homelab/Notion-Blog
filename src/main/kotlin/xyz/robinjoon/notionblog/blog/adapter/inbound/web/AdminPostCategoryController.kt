package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPostCategoriesView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.CategoryOptionView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesOptionView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SourceTagOptionView
import xyz.robinjoon.notionblog.blog.application.model.PostClassification
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SourceTagCandidate
import xyz.robinjoon.notionblog.blog.application.port.input.PostClassificationUseCase
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 글 하나의 분류 편집(태그·시리즈·Notion 원문 태그 옮기기). 한 번에 저장하며 오류 결과는 아무것도 바꾸지 않은 것이다.
// 저장해도 본문과 공개 상태는 바뀌지 않는다.
@Controller
internal class AdminPostCategoryController(
    private val classifications: PostClassificationUseCase,
) {
    @GetMapping("/admin/posts/{postId}/categories")
    fun edit(
        @PathVariable postId: String,
        model: Model,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        return when (val result = classifications.get(id)) {
            is PostClassificationLookupResult.Found -> {
                ModelAndView(
                    "admin/post-categories",
                ).addObject("view", view(result.classification, model))
            }

            PostClassificationLookupResult.NotFound -> {
                notFound()
            }
        }
    }

    // 선택한 태그 전체로 교체하고(빈 선택은 해제), 시리즈는 하나만 고르며(빈 값은 해제), 고른 원문 태그는 태그로 옮겨 이 글에 추가한다.
    @PostMapping("/admin/posts/{postId}/categories")
    fun save(
        @PathVariable postId: String,
        form: AdminClassificationForm,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        val input = form.toInput() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val path = "${postPath(id)}/categories"
        return when (classifications.save(id, input.categoryIds, input.seriesId, input.sourceTagNames)) {
            PostClassificationChangeResult.Saved -> redirect(path, attributes, SAVED)
            PostClassificationChangeResult.CategoryNotFound -> redirect(path, attributes, "삭제되었거나 없는 태그가 포함되어 있습니다. 다시 선택하세요.", true)
            PostClassificationChangeResult.SeriesNotFound -> redirect(path, attributes, "삭제되었거나 없는 시리즈입니다. 다시 선택하세요.", true)
            PostClassificationChangeResult.SourceTagNotAvailable -> redirect(path, attributes, SOURCE_TAG_UNAVAILABLE, true)
            PostClassificationChangeResult.PostNotFound -> notFound()
        }
    }

    private fun view(
        classification: PostClassification,
        model: Model,
    ): AdminPostCategoriesView =
        AdminPostCategoriesView(
            classification.postId.value.toString(),
            classification.title,
            classification.categories.map {
                CategoryOptionView(it.id.value.toString(), it.name, it.assignedPosts, it.id in classification.selectedCategoryIds)
            },
            classification.series.map {
                SeriesOptionView(
                    it.id.value.toString(),
                    it.title,
                    it.postCount,
                    it.id == classification.selectedSeriesId,
                )
            },
            classification.sourceTags.map { sourceTag(it, classification) },
            model.notice(),
        )

    // 같은 이름의 태그가 있으면 재사용하고 이미 이 글에 지정됐으면 그렇게 알린다. 없으면 저장할 때 새로 만든다.
    private fun sourceTag(
        candidate: SourceTagCandidate,
        classification: PostClassification,
    ): SourceTagOptionView =
        SourceTagOptionView(
            candidate.name,
            classification.categories.firstOrNull { it.id == candidate.existingCategoryId }?.name,
            candidate.assigned,
            candidate.name.length > Category.MAX_NAME_LENGTH,
        )

    private fun notFound(): ModelAndView = errorView("error/blog-not-found", HttpStatus.NOT_FOUND)

    private companion object {
        const val SAVED = "분류를 저장했습니다. 공개 중인 글의 태그와 시리즈에 바로 반영되며 본문과 공개 상태는 바뀌지 않습니다."
        const val SOURCE_TAG_UNAVAILABLE =
            "선택한 원문 태그를 옮길 수 없습니다. 공개 글은 공개 중인 내용, 그 밖의 글은 최신 가져온 내용에 있고 " +
                "이름이 1~50자인 원문 태그만 옮길 수 있습니다. 화면을 새로 고쳐 다시 선택하세요."
    }
}

// 체크박스와 라디오는 선택하지 않으면 값이 전송되지 않으므로 생성자 기본값을 비어 있는 선택으로 두고 불변 값으로 바인딩한다.
internal data class AdminClassificationForm(
    val categoryId: List<String> = emptyList(),
    val seriesId: String = "",
    val sourceTag: List<String> = emptyList(),
) {
    // 형식이 틀린 식별자가 하나라도 있으면 null이다. 빈 시리즈 값은 소속 해제다.
    fun toInput(): ClassificationInput? {
        val categories = categoryId.map { WebIds.categoryId(it) ?: return null }.toSet()
        val series = seriesId.takeIf(String::isNotBlank)?.let { WebIds.seriesId(it) ?: return null }
        return ClassificationInput(categories, series, sourceTag.toSet())
    }
}

internal data class ClassificationInput(
    val categoryIds: Set<CategoryId>,
    val seriesId: SeriesId?,
    val sourceTagNames: Set<String>,
)
