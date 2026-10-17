package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration

// 홈 글 목록 설정은 하나뿐이며 관리자가 처음 저장하기 전에는 없다.
interface HomeConfigurationRepository {
    fun find(): HomeConfiguration?

    // 단일 설정을 추가하거나 덮어쓴다.
    fun save(configuration: HomeConfiguration)
}
