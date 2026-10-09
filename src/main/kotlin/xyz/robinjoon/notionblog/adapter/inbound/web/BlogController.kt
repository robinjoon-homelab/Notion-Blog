package xyz.robinjoon.notionblog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView
import xyz.robinjoon.notionblog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.application.port.input.GetBlogPageUseCase
import xyz.robinjoon.notionblog.domain.post.PostId
import java.util.UUID

@Controller
class BlogController(
    private val blogPages: GetBlogPageUseCase,
    private val assembler: PostPageViewAssembler,
) {
    @GetMapping("/")
    fun root(): ModelAndView = render(blogPages.getRoot())

    @GetMapping("/posts/{postId}")
    fun post(
        @PathVariable postId: String,
    ): ModelAndView {
        val parsedPostId = parsePostId(postId) ?: return errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
        return render(blogPages.get(parsedPostId))
    }

    private fun render(result: BlogPageLookupResult): ModelAndView =
        when (result) {
            is BlogPageLookupResult.Found -> {
                ModelAndView("blog/post").apply {
                    addObject("page", assembler.assemble(result.page))
                    status = HttpStatus.OK
                }
            }

            BlogPageLookupResult.NotFound -> {
                errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
            }

            BlogPageLookupResult.ContentUnavailable -> {
                errorView("error/blog-unavailable", HttpStatus.SERVICE_UNAVAILABLE)
            }
        }

    private fun parsePostId(value: String): PostId? {
        if (!value.matches(uuidPattern)) {
            return null
        }
        return runCatching { PostId(UUID.fromString(value)) }.getOrNull()
    }

    private fun errorView(
        viewName: String,
        status: HttpStatus,
    ): ModelAndView =
        ModelAndView(viewName).apply {
            this.status = status
        }

    private companion object {
        val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
