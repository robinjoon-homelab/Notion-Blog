package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import xyz.robinjoon.notionblog.blog.application.model.ArchivePostResult
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.PublishPostResult
import xyz.robinjoon.notionblog.blog.application.model.RefreshPostResult
import xyz.robinjoon.notionblog.blog.application.model.RestorePostResult
import xyz.robinjoon.notionblog.blog.application.model.UnpublishPostResult
import xyz.robinjoon.notionblog.blog.application.port.input.PostPublicationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.RefreshPostUseCase

// 가져오기·발행·비공개로 전환·보관·복원. 모두 POST이며 CSRF 토큰 없이는 보안 필터에서 거절된다.
@Controller
internal class AdminPostCommandController(
    private val refreshes: RefreshPostUseCase,
    private val publications: PostPublicationUseCase,
) {
    @PostMapping("/admin/posts/{postId}/refresh")
    fun refresh(
        @PathVariable postId: String,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        val path = postPath(id)
        return when (refreshes.refresh(id)) {
            is RefreshPostResult.Updated -> redirect(path, attributes, "Notion의 최신 내용을 가져왔습니다. 발행 확인에서 내용을 확인한 뒤 발행하세요.")
            RefreshPostResult.Unchanged -> redirect(path, attributes, "새로 바뀐 내용이 없습니다.")
            is RefreshPostResult.Failed -> redirect(path, attributes, "최신 내용을 가져오지 못했습니다. 공개 중인 내용은 그대로입니다.", true)
            RefreshPostResult.NotFound -> notFound()
        }
    }

    @PostMapping("/admin/posts/{postId}/publish")
    fun publish(
        @PathVariable postId: String,
        @RequestParam revisionId: String,
        @RequestParam generation: Long,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        val revision = WebIds.revisionId(revisionId) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val path = postPath(id)
        return when (publications.publish(PublishPostCommand(id, revision, generation))) {
            PublishPostResult.Published -> redirect(path, attributes, "검토한 내용을 발행했습니다. 내 블로그에 반영됩니다.")
            PublishPostResult.AlreadyPublished -> redirect(path, attributes, "이미 발행된 내용입니다.")
            PublishPostResult.StaleRevision -> redirect(reviewPath(id), attributes, "새 변경이 있어 발행하지 않았습니다. 최신 내용을 다시 확인한 뒤 발행하세요.", true)
            PublishPostResult.MediaNotReady -> redirect(reviewPath(id), attributes, "파일을 준비하는 중입니다. 잠시 뒤 최신 내용을 다시 가져오세요.", true)
            PublishPostResult.Archived -> redirect(path, attributes, "보관된 글은 발행할 수 없습니다. 먼저 보관을 해제하세요.", true)
            PublishPostResult.NotFound -> notFound()
        }
    }

    @PostMapping("/admin/posts/{postId}/unpublish")
    fun unpublish(
        @PathVariable postId: String,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        val path = postPath(id)
        return when (publications.unpublish(id)) {
            UnpublishPostResult.Unpublished -> redirect(path, attributes, "비공개로 전환했습니다. 원문과 가져온 내용은 보존됩니다.")
            UnpublishPostResult.AlreadyUnpublished -> redirect(path, attributes, "이미 공개되지 않은 글입니다.")
            UnpublishPostResult.NotFound -> notFound()
        }
    }

    @PostMapping("/admin/posts/{postId}/archive")
    fun archive(
        @PathVariable postId: String,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        return when (publications.archive(id)) {
            ArchivePostResult.Archived -> redirect(postPath(id), attributes, "글을 보관했습니다. 공개가 중지되고 기록은 남습니다.")
            ArchivePostResult.AlreadyArchived -> redirect(postPath(id), attributes, "이미 보관된 글입니다.")
            ArchivePostResult.NotFound -> notFound()
        }
    }

    @PostMapping("/admin/posts/{postId}/restore")
    fun restore(
        @PathVariable postId: String,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.postId(postId) ?: return notFound()
        return when (publications.restore(id)) {
            RestorePostResult.Restored -> redirect(postPath(id), attributes, "보관을 해제했습니다. 비공개 상태이며 공개하려면 발행하세요.")
            RestorePostResult.NotArchived -> redirect(postPath(id), attributes, "보관된 글이 아닙니다.")
            RestorePostResult.NotFound -> notFound()
        }
    }

    private fun notFound(): ModelAndView = errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
}
