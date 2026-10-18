package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ActionLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.EmptyStateView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesCardView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesEntryView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesListPageView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesPageView
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesPage

// 공개 시리즈 목록과 시리즈 안의 순서 목록을 화면 모델로 만든다. 입력은 공개 글만으로 계산된 값이다.
class SeriesPageViewAssembler(
    private val pages: PostPageViewAssembler,
) {
    internal fun assemble(page: PublicSeriesListPage): SeriesListPageView {
        val cards =
            page.series.map {
                SeriesCardView(
                    href = HomeLinks.series(it.id),
                    title = it.title,
                    description = it.description.takeIf(String::isNotBlank),
                    postCount = it.postCount,
                    thumbnailHref = pages.thumbnail(it.firstPost, page.references),
                )
            }
        return SeriesListPageView(
            chrome = pages.publicChrome(page.chrome, page.references),
            tabs = homeTabs(seriesCurrent = true),
            cards = cards,
            empty = EmptyStateView("아직 공개된 시리즈가 없습니다.", listOf(ActionLinkView("전체 글 보기", "/"))).takeIf { cards.isEmpty() },
        )
    }

    // 순번은 시리즈 순서이며 홈의 정렬 설정을 따르지 않는다. 요약·날짜만 홈 표시 설정을 따른다.
    internal fun assemble(page: PublicSeriesPage): SeriesPageView {
        val details = page.configuration.details
        val locale = pages.locale(page.chrome)
        return SeriesPageView(
            chrome = pages.publicChrome(page.chrome, page.references),
            tabs = homeTabs(seriesCurrent = true),
            title = page.series.title,
            description = page.series.description.takeIf(String::isNotBlank),
            entries =
                page.series.posts.mapIndexed { index, entry ->
                    SeriesEntryView(
                        ordinal = index + 1,
                        href = HomeLinks.post(entry.postId),
                        title = entry.properties.title,
                        summary = entry.properties.summary.takeIf { details.showSummary },
                        publishedOn = pages.date(entry.firstPublishedAt, locale).takeIf { details.showDate },
                    )
                },
        )
    }
}
