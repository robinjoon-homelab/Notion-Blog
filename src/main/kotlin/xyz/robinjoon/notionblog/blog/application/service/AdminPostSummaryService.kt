package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.AdminPostActions
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.AdminPostSummary
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.SourceHealth
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
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
                status = status(post),
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

    private fun status(post: Post): AdminPostStatus {
        val publication = post.publication
        return when {
            publication.archived -> AdminPostStatus.ARCHIVED
            !publication.isPublic && publication.publishedRevisionId == null -> AdminPostStatus.DRAFT
            !publication.isPublic -> AdminPostStatus.UNPUBLISHED
            post.latestRevisionId == publication.publishedRevisionId -> AdminPostStatus.PUBLISHED
            else -> AdminPostStatus.CHANGES_PENDING
        }
    }

    private companion object {
        const val UNTITLED = "Untitled"
    }
}
