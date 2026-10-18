package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicSeriesUseCase

// 공개 시리즈 목록과 시리즈 안의 순서 목록. 공개 글이 없는 시리즈는 존재하지 않는 것과 같게 404로 답한다.
@Controller
class SeriesController(
    private val series: GetPublicSeriesUseCase,
    private val assembler: SeriesPageViewAssembler,
) {
    @GetMapping("/series")
    fun list(): ModelAndView =
        when (val result = series.list()) {
            is PublicSeriesListLookupResult.Found -> ModelAndView("blog/series-list").addObject("page", assembler.assemble(result.page))
            PublicSeriesListLookupResult.ContentUnavailable -> errorView("error/blog-unavailable", HttpStatus.SERVICE_UNAVAILABLE)
        }

    @GetMapping("/series/{seriesId}")
    fun detail(
        @PathVariable seriesId: String,
    ): ModelAndView {
        val id = WebIds.seriesId(seriesId) ?: return errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
        return when (val result = series.get(id)) {
            is PublicSeriesLookupResult.Found -> ModelAndView("blog/series").addObject("page", assembler.assemble(result.page))
            PublicSeriesLookupResult.NotFound -> errorView("error/blog-not-found", HttpStatus.NOT_FOUND)
            PublicSeriesLookupResult.ContentUnavailable -> errorView("error/blog-unavailable", HttpStatus.SERVICE_UNAVAILABLE)
        }
    }
}
