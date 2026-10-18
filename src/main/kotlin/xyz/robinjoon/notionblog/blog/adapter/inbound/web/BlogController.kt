package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import xyz.robinjoon.notionblog.blog.application.model.HomePageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPostListUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicPostUseCase

@Controller
class BlogController(
    private val postLists: GetPostListUseCase,
    private val posts: GetPublicPostUseCase,
    private val assembler: PostPageViewAssembler,
    private val homes: HomePageViewAssembler,
) {
    // 태그는 tag가 정식이고 옛 category 주소도 같은 응답을 준다. 검색어가 너무 길면 자르지 않고 검색 없이 400으로 안내한다.
    @GetMapping("/")
    fun home(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) q: String?,
    ): ModelAndView {
        // 형식이 틀린 태그 값은 없는 태그와 같게 404로 처리한다.
        val categoryId = (tag ?: category)?.let { WebIds.categoryId(it) ?: return errorView("error/blog-not-found", HttpStatus.NOT_FOUND) }
        val input = HomeSearchInput(q.orEmpty())
        return when (val result = postLists.get(HomePageQuery(page.coerceAtLeast(1), categoryId, input.query))) {
            is PostListLookupResult.Found -> {
                ModelAndView("blog/home")
                    .addObject("page", homes.assemble(result.page, input))
                    .apply { if (input.tooLong) status = HttpStatus.BAD_REQUEST }
            }

            PostListLookupResult.NotFound -> {
                errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
            }

            PostListLookupResult.ContentUnavailable -> {
                errorView("error/blog-unavailable", HttpStatus.SERVICE_UNAVAILABLE)
            }
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
}
