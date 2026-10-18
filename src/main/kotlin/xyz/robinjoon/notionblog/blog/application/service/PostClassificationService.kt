package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.PostClassification
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SourceTagCandidate
import xyz.robinjoon.notionblog.blog.application.port.input.PostClassificationUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesRepository
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 글의 태그·시리즈 소속만 바꾸며 게시 상태·세대·리비전과 Notion 원문 태그는 그대로 둔다. 분류 저장은 한 번에 반영되어야 하므로
// 태그 행 → 시리즈 행 → 글 순서로 모두 잠근 뒤, 잠근 글의 현재 게시 상태로 원문 태그 후보를 다시 판정하고 나서야 처음 쓴다. 그래서
// Saved가 아닌 결과는 아무것도 바꾸지 않는다. 분류 삭제와 시리즈 명령도 같은 순서로 잠근다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class PostClassificationService(
    private val posts: PostRepository,
    private val categories: CategoryRepository,
    private val series: SeriesRepository,
    private val categoryIdFactory: () -> CategoryId,
) : PostClassificationUseCase {
    @Transactional(readOnly = true)
    override fun get(postId: PostId): PostClassificationLookupResult {
        val post = posts.find(postId) ?: return PostClassificationLookupResult.NotFound
        val title = posts.findTitles(setOf(postId))[postId] ?: UNTITLED
        val allSeries = series.findAll()
        val classification =
            PostClassification(
                postId = postId,
                title = title,
                categories = categories.overviews(),
                selectedCategoryIds = post.categoryIds,
                series = allSeries.overviews(posts.findAllManaged()),
                selectedSeriesId = allSeries.firstOrNull { postId in it.postIds }?.id,
                sourceTags = candidates(post),
            )
        return PostClassificationLookupResult.Found(classification)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun save(
        postId: PostId,
        categoryIds: Set<CategoryId>,
        seriesId: SeriesId?,
        sourceTagNames: Set<String>,
    ): PostClassificationChangeResult {
        if (posts.find(postId) == null) return PostClassificationChangeResult.PostNotFound
        val names = sourceTagNames.mapTo(linkedSetOf(), String::trim)
        val lockedCategories = lockCategories(categoryIds, names) ?: return PostClassificationChangeResult.CategoryNotFound
        val lockedSeries = lockSeries(postId, seriesId) ?: return PostClassificationChangeResult.SeriesNotFound
        val post = posts.lock(postId) ?: return PostClassificationChangeResult.PostNotFound
        if (!isAdoptable(names, post)) return PostClassificationChangeResult.SourceTagNotAvailable
        val adopted = adopt(names, lockedCategories)
        move(postId, seriesId, lockedSeries)
        posts.saveCategories(post.categorize(categoryIds + adopted))
        return PostClassificationChangeResult.Saved
    }

    private fun candidates(post: Post): List<SourceTagCandidate> {
        val byName = categories.findAll().associateBy(Category::normalizedName)
        return sourceTags(post).map { name ->
            val existing = byName[Category.normalize(name)]
            SourceTagCandidate(name, existing?.id, existing != null && existing.id in post.categoryIds)
        }
    }

    // 공개 글은 공개본, 그 밖의 글은 최신 리비전의 원문 태그다. 태그 이름 규칙보다 긴 원문 태그도 그대로 보여 주고 저장에서 거절한다.
    private fun sourceTags(post: Post): List<String> {
        val publication = post.publication
        val revisionId = (if (publication.isPublic) publication.publishedRevisionId else post.latestRevisionId) ?: return emptyList()
        val tags =
            posts
                .findRevision(post.id, revisionId)
                ?.properties
                ?.tags
                .orEmpty()
        return tags.map(String::trim).filter(String::isNotEmpty).distinct()
    }

    private fun isAdoptable(
        names: Set<String>,
        post: Post,
    ): Boolean = names.all { it.length <= Category.MAX_NAME_LENGTH } && sourceTags(post).containsAll(names)

    // 선택한 태그와, 원문 태그와 이름이 같은 태그를 ID 순서로 함께 잠근다. 선택한 태그가 하나라도 없으면 null이다.
    private fun lockCategories(
        categoryIds: Set<CategoryId>,
        names: Set<String>,
    ): List<Category>? {
        val normalized = names.mapTo(hashSetOf()) { Category.normalize(it) }
        val sameName = categories.findAll().filter { it.normalizedName in normalized }.map(Category::id)
        val locked = categories.lock(categoryIds + sameName)
        return locked.takeIf { it.map(Category::id).containsAll(categoryIds) }
    }

    // 소속이 바뀔 때만 지금 시리즈와 대상 시리즈를 ID 순서로 잠근다. 대상 시리즈가 없으면 null이다.
    private fun lockSeries(
        postId: PostId,
        seriesId: SeriesId?,
    ): List<Series>? {
        val current = series.findAll().firstOrNull { postId in it.postIds }?.id
        if (current == seriesId) return emptyList()
        val locked = series.lock(setOfNotNull(current, seriesId))
        return locked.takeIf { seriesId == null || locked.any { it.id == seriesId } }
    }

    // 원문 태그마다 대소문자를 구분하지 않고 같은 이름의 잠근 태그를 쓰고, 없으면 마지막 표시 순서 뒤에 새로 만든다.
    private fun adopt(
        names: Set<String>,
        locked: List<Category>,
    ): Set<CategoryId> {
        if (names.isEmpty()) return emptySet()
        val existing = locked.associateBy(Category::normalizedName)
        var nextPosition = (categories.findAll().maxOfOrNull(Category::position) ?: -1) + 1
        return names.distinctBy { Category.normalize(it) }.mapTo(linkedSetOf()) { name ->
            existing[Category.normalize(name)]?.id ?: create(name, nextPosition++)
        }
    }

    private fun create(
        name: String,
        position: Int,
    ): CategoryId {
        val category = Category.create(categoryIdFactory(), name, position)
        categories.save(category)
        return category.id
    }

    // 글이 두 시리즈에 동시에 속하지 않도록 지금 시리즈에서 먼저 빼고 대상 시리즈의 끝에 넣는다.
    private fun move(
        postId: PostId,
        seriesId: SeriesId?,
        locked: List<Series>,
    ) {
        locked.filter { it.id != seriesId && postId in it.postIds }.forEach { current -> series.save(current.remove(postId)) }
        locked.filter { it.id == seriesId }.forEach { target -> series.save(target.append(postId)) }
    }

    private companion object {
        const val UNTITLED = "Untitled"
    }
}
