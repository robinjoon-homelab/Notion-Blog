package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.AdminPostActions
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.AdminPostSummary
import xyz.robinjoon.notionblog.blog.application.model.CurrentClassification
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.SeriesMembership
import xyz.robinjoon.notionblog.blog.application.model.SourceHealth
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostCatalog
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget

// 관리 화면의 상태와 버튼은 저장된 게시 상태에서만 계산하며 원본에는 묻지 않는다.
@Transactional(readOnly = true)
class AdminPostSummaryService(
    private val posts: PostRepository,
    private val syncStates: SyncStateRepository,
    private val catalog: PostCatalog,
    private val categories: CategoryRepository,
    private val series: SeriesRepository,
) {
    @Transactional(readOnly = true)
    fun summarize(managed: List<Post>): List<AdminPostSummary> {
        val shown = managed.flatMapTo(linkedSetOf()) { listOfNotNull(it.latestRevisionId, it.publication.publishedRevisionId) }
        val revisions = posts.findRevisionSummaries(shown)
        val unbound = posts.findUnboundMedia(shown)
        val titles = posts.findTitles(managed.mapTo(linkedSetOf()) { it.id })
        val states = syncStates.findAll(managed.mapTo(linkedSetOf()) { SyncTarget.Post(it.id) })
        return managed.map { post ->
            val state = states[SyncTarget.Post(post.id)]
            AdminPostSummary(
                postId = post.id,
                title = titles[post.id] ?: UNTITLED,
                status = post.adminStatus(),
                latestCapturedAt = post.latestRevisionId?.let(revisions::get)?.capturedAt,
                source = SourceHealth(catalog.editUrl(post.source), state?.lastSuccessAt, state?.lastErrorKind),
                missingMedia = (post.publication.publishedRevisionId ?: post.latestRevisionId)?.let(unbound::get)?.size ?: 0,
            )
        }
    }

    // 게시 버튼은 최신 리비전을 보여 주고 있고 그 리비전이 아직 게시되지 않았을 때만 제공한다.
    fun actions(
        post: Post,
        shownRevisionId: PostRevisionId?,
    ): AdminPostActions {
        val publication = post.publication
        val latest = post.latestRevisionId
        val publishable =
            latest != null && !publication.archived && shownRevisionId == latest &&
                !(publication.isPublic && publication.publishedRevisionId == latest)
        return AdminPostActions(
            publish = if (publishable) PublishPostCommand(post.id, latest, publication.generation) else null,
            canUnpublish = publication.isPublic,
            canArchive = !publication.archived,
            canRestore = publication.archived,
        )
    }

    // 발행 확인 화면의 현재 분류다. 시리즈 위치는 비공개·보관 글을 포함한 전체 소속 기준이다.
    @Transactional(readOnly = true)
    fun classification(post: Post): CurrentClassification {
        val assigned = categories.findAll().filter { it.id in post.categoryIds }.map { ListedCategory(it.id, it.name) }
        val owner = series.findAll().firstOrNull { post.id in it.postIds }
        val membership = owner?.let { SeriesMembership(it.id, it.title, it.postIds.indexOf(post.id) + 1, it.postIds.size) }
        return CurrentClassification(assigned, membership)
    }

    private companion object {
        const val UNTITLED = "Untitled"
    }
}

internal fun Post.adminStatus(): AdminPostStatus =
    when {
        publication.archived -> AdminPostStatus.ARCHIVED
        !publication.isPublic && publication.publishedRevisionId == null -> AdminPostStatus.DRAFT
        !publication.isPublic -> AdminPostStatus.UNPUBLISHED
        latestRevisionId == publication.publishedRevisionId -> AdminPostStatus.PUBLISHED
        else -> AdminPostStatus.CHANGES_PENDING
    }
