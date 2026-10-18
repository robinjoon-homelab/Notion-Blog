package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// assignedPosts는 초안·보관 글까지 센다. 0이 아니면 삭제할 수 없다.
data class CategoryOverview(
    val id: CategoryId,
    val name: String,
    val position: Int,
    val assignedPosts: Int,
)

sealed interface CategoryChangeResult {
    data class Saved(
        val id: CategoryId,
    ) : CategoryChangeResult

    data object NotFound : CategoryChangeResult

    // 대소문자만 다른 이름도 같은 이름으로 본다.
    data object NameInUse : CategoryChangeResult

    data object AssignedToPosts : CategoryChangeResult
}

// 원문 태그 후보다. existingCategoryId는 대소문자를 구분하지 않고 이름이 같은 태그이며, 없으면 저장할 때 새로 만든다. assigned는 그 태그가
// 이미 이 글에 배정되었는지다. 이름이 Category.MAX_NAME_LENGTH보다 길면 보여 주기만 하고 옮길 수 없다.
data class SourceTagCandidate(
    val name: String,
    val existingCategoryId: CategoryId?,
    val assigned: Boolean,
)

// 분류 편집 화면의 현재 값이다. sourceTags는 공개 글이면 공개본, 그 밖에는 최신 리비전의 Notion 원문 태그다.
data class PostClassification
    // quality-exception: 분류 편집 화면이 한 번에 보여 주는 태그·시리즈·원문 태그 후보 7개 필드를 보존하는 조회 결과다.
    @Suppress("LongParameterList")
    constructor(
        val postId: PostId,
        val title: String,
        val categories: List<CategoryOverview>,
        val selectedCategoryIds: Set<CategoryId>,
        val series: List<SeriesOverview>,
        val selectedSeriesId: SeriesId?,
        val sourceTags: List<SourceTagCandidate>,
    )

sealed interface PostClassificationLookupResult {
    data class Found(
        val classification: PostClassification,
    ) : PostClassificationLookupResult

    data object NotFound : PostClassificationLookupResult
}

// Saved가 아닌 결과는 아무것도 저장하지 않았다는 뜻이다.
sealed interface PostClassificationChangeResult {
    data object Saved : PostClassificationChangeResult

    data object PostNotFound : PostClassificationChangeResult

    data object CategoryNotFound : PostClassificationChangeResult

    data object SeriesNotFound : PostClassificationChangeResult

    // 저장 시점의 원문 태그 후보가 아니거나 태그 이름 규칙에 맞지 않는다.
    data object SourceTagNotAvailable : PostClassificationChangeResult
}
