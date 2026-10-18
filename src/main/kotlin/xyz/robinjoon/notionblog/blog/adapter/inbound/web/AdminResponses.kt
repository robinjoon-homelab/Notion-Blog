package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.ui.Model
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// POST 뒤에는 항상 303으로 돌려보내고 결과 문구는 한 번만 보이는 플래시로 전달한다.
internal fun redirect(
    path: String,
    attributes: RedirectAttributes,
    text: String,
    error: Boolean = false,
): ModelAndView {
    attributes.addFlashAttribute("notice", NoticeView(text, error))
    return ModelAndView("redirect:$path").apply { status = HttpStatus.SEE_OTHER }
}

internal fun errorView(
    viewName: String,
    status: HttpStatus,
): ModelAndView = ModelAndView(viewName).apply { this.status = status }

internal fun postPath(postId: PostId): String = "/admin/posts/${postId.value}"

internal fun reviewPath(postId: PostId): String = "${postPath(postId)}/review"

// 리다이렉트 직후 한 번만 보이는 플래시 알림을 꺼낸다.
internal fun Model.notice(): NoticeView? = asMap()["notice"] as? NoticeView
