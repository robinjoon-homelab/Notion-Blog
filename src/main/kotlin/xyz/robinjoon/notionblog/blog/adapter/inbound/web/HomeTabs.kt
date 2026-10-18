package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.HomeTabView

// 내 블로그 머리의 글·시리즈 전환이다. 소개 탭은 만들지 않는다.
internal fun homeTabs(seriesCurrent: Boolean): List<HomeTabView> =
    listOf(
        HomeTabView("글", "/", !seriesCurrent),
        HomeTabView("시리즈", "/series", seriesCurrent),
    )
