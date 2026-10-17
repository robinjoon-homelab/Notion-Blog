package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.AdminPostActions
import xyz.robinjoon.notionblog.blog.application.model.AdminPostHistory
import xyz.robinjoon.notionblog.blog.application.model.AdminPostList
import xyz.robinjoon.notionblog.blog.application.model.AdminPostPreview
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.AdminPostSummary
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.model.SourceHealth
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import java.net.URI
import java.time.Instant
import java.util.UUID

// 관리 화면 테스트가 함께 쓰는 읽기 모델 조립.
internal object AdminFixtures {
    val publishedHistory = AdminPostHistory(WebFixtures.revisionId, Instant.parse("2026-09-01T00:00:00Z"))
    val publishCommand = PublishPostCommand(WebFixtures.postId, WebFixtures.revisionId, 7)

    fun summary(
        title: String,
        status: AdminPostStatus,
        missingMedia: Int = 0,
    ) = AdminPostSummary(
        PostId(UUID.nameUUIDFromBytes(title.toByteArray())),
        title,
        status,
        Instant.parse("2026-10-16T05:00:00Z"),
        SourceHealth(URI("https://www.notion.so/page"), null, null),
        missingMedia,
    )

    fun list(
        posts: List<AdminPostSummary>,
        query: PageQuery = PageQuery(1, 20),
    ) = AdminPostList(ResultPage.of(posts, query))

    fun actions(
        publish: PublishPostCommand? = publishCommand,
        canUnpublish: Boolean = false,
        canArchive: Boolean = true,
        canRestore: Boolean = false,
    ) = AdminPostActions(publish, canUnpublish, canArchive, canRestore)

    fun preview(
        status: AdminPostStatus,
        actions: AdminPostActions,
        history: AdminPostHistory = AdminPostHistory(null, null),
    ) = AdminPostPreview(
        summary = summary("Hello", status).copy(postId = WebFixtures.postId),
        chrome = WebFixtures.chrome(),
        revision = WebFixtures.revision(properties = PostProperties("Hello", "A summary", listOf("kotlin"), null)),
        references = ContentReferences(emptyMap(), RevisionMedia.empty),
        actions = actions,
        history = history,
    )
}
