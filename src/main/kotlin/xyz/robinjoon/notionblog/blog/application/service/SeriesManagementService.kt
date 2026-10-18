package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.SeriesChangeResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesEditor
import xyz.robinjoon.notionblog.blog.application.model.SeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostSummary
import xyz.robinjoon.notionblog.blog.application.port.input.SeriesManagementUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesRepository
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 시리즈 명령은 글·태그·게시 상태를 바꾸지 않는다. 저장은 옮겨 오는 글을 가진 다른 시리즈와 대상 시리즈를 ID 순서로 잠근 뒤
// 판정하고, 다른 시리즈에서 빼는 쓰기를 먼저 한다. 잠그지 못한 시리즈가 그사이 같은 글을 가져가면 저장소가
// SeriesMembershipConflictException을 던져 전체가 롤백된다. Exposed는 SQL 실패를 checked 예외로 알리므로 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class SeriesManagementService(
    private val posts: PostRepository,
    private val series: SeriesRepository,
    private val seriesIdFactory: () -> SeriesId,
) : SeriesManagementUseCase {
    @Transactional(readOnly = true)
    override fun list(): List<SeriesOverview> = series.findAll().overviews(posts.findAllManaged())

    @Transactional(readOnly = true)
    override fun get(id: SeriesId): SeriesLookupResult {
        val all = series.findAll()
        val target = all.firstOrNull { it.id == id } ?: return SeriesLookupResult.NotFound
        val managed = posts.findAllManaged()
        val byId = managed.associateBy(Post::id)
        val titles = posts.findTitles(byId.keys)
        val owners = all.flatMap { owner -> owner.postIds.map { postId -> postId to owner.title } }.toMap()
        val members = target.postIds.mapNotNull(byId::get).map { post -> summary(post, titles, null) }
        val candidates =
            managed
                .filter { post -> post.id !in target.postIds && !post.publication.archived }
                .map { post -> summary(post, titles, owners[post.id]) }
                .sortedWith(compareBy<SeriesPostSummary> { it.title }.thenBy { it.postId.value })
        return SeriesLookupResult.Found(SeriesEditor(target.overview(publicPostIds(managed)), members, candidates))
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun create(
        title: String,
        description: String,
    ): SeriesChangeResult {
        val created = Series.create(seriesIdFactory(), title, description)
        series.save(created)
        return SeriesChangeResult.Saved(created.id)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun save(
        id: SeriesId,
        title: String,
        description: String,
        postIds: List<PostId>,
    ): SeriesChangeResult {
        val managed = posts.findAllManaged().mapTo(hashSetOf(), Post::id)
        if (!managed.containsAll(postIds)) return SeriesChangeResult.PostNotFound
        val moving = postIds.toSet()
        val owners = series.findAll().filter { other -> other.id != id && other.postIds.any(moving::contains) }
        val locked = series.lock(owners.mapTo(linkedSetOf(id), Series::id))
        val updated = locked.firstOrNull { it.id == id }?.update(title, description, postIds) ?: return SeriesChangeResult.NotFound
        locked
            .filter { other -> other.id != id && other.postIds.any(moving::contains) }
            .forEach { other -> series.save(moving.fold(other) { remaining, postId -> remaining.remove(postId) }) }
        series.save(updated)
        return SeriesChangeResult.Saved(id)
    }

    // 소속 행과 시리즈 행만 지우므로 글·태그·게시 상태는 그대로다.
    @Transactional(rollbackFor = [Exception::class])
    override fun delete(id: SeriesId): SeriesChangeResult {
        if (series.lock(setOf(id)).isEmpty()) return SeriesChangeResult.NotFound
        series.delete(id)
        return SeriesChangeResult.Saved(id)
    }

    private fun summary(
        post: Post,
        titles: Map<PostId, String>,
        currentSeriesTitle: String?,
    ): SeriesPostSummary = SeriesPostSummary(post.id, titles[post.id] ?: UNTITLED, post.adminStatus(), currentSeriesTitle)

    private companion object {
        const val UNTITLED = "Untitled"
    }
}

// 공개 글 수는 게시 상태만 보므로 사이트 문서도 센다. 공개 화면은 사이트 문서를 따로 뺀다.
internal fun List<Series>.overviews(managed: List<Post>): List<SeriesOverview> {
    val publicPostIds = publicPostIds(managed)
    return map { it.overview(publicPostIds) }
}

private fun publicPostIds(managed: List<Post>): Set<PostId> =
    managed.filter { post -> post.publication.isPublic }.mapTo(hashSetOf(), Post::id)

private fun Series.overview(publicPostIds: Set<PostId>): SeriesOverview =
    SeriesOverview(id, title, description, postIds.size, postIds.count(publicPostIds::contains))
