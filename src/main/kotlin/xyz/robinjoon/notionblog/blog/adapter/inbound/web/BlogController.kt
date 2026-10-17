package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase

@Controller
class BlogController(
    private val postLists: GetPostListUseCase,
    private val posts: GetPublicPostUseCase,
    private val assembler: PostPageViewAssembler,
) {
    @GetMapping("/")
    fun home(
        @RequestParam(defaultValue = "1") page: Int,
    ): ModelAndView =
        when (val result = postLists.get(PageQuery(page.coerceAtLeast(1), HOME_PAGE_SIZE))) {
            is PostListLookupResult.Found -> {
                ModelAndView("blog/home").addObject("page", assembler.assemble(result.page))
            }

            PostListLookupResult.ContentUnavailable -> {
                errorView("error/blog-unavailable", HttpStatus.SERVICE_UNAVAILABLE)
            }
        }

    @GetMapping("/posts/{postId}")
    fun post(
        @PathVariable postId: String,
    ): ModelAndView {
        val parsedPostId = WebIds.postId(postId) ?: return errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
        return when (val result = posts.get(parsedPostId)) {
            is PublicPostLookupResult.Found -> ModelAndView("blog/post").addObject("page", assembler.assemble(result.page))
            PublicPostLookupResult.NotFound -> errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
            PublicPostLookupResult.ContentUnavailable -> errorView("error/blog-unavailable", HttpStatus.SERVICE_UNAVAILABLE)
        }
    }

    private companion object {
        const val HOME_PAGE_SIZE = 10
    }
}
