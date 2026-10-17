package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.port.input.HomeConfigurationUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration

// 홈 글 목록 설정은 Notion 설정 동기화와 무관하게 앱 DB에만 저장한다. 저장한 적이 없으면 기존 목록과 같은 기본값을 쓴다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class HomeConfigurationService(
    private val configurations: HomeConfigurationRepository,
) : HomeConfigurationUseCase {
    @Transactional(readOnly = true)
    override fun get(): HomeConfiguration = configurations.find() ?: HomeConfiguration.defaults()

    @Transactional(rollbackFor = [Exception::class])
    override fun update(configuration: HomeConfiguration) {
        configurations.save(configuration)
    }
}
