package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration

// 공개 홈 글 목록의 표시 방식이다. 저장한 설정만 공개 홈에 반영된다.
interface HomeConfigurationUseCase {
    // 저장한 적이 없으면 기본값이다.
    fun get(): HomeConfiguration

    fun update(configuration: HomeConfiguration)
}
