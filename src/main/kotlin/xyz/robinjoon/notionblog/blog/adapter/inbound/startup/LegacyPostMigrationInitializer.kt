package xyz.robinjoon.notionblog.blog.adapter.inbound.startup

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import xyz.robinjoon.notionblog.blog.application.port.input.MigrateLegacyPostsUseCase

// 모든 싱글턴이 생성된 뒤, 웹 서버나 예약 작업이 시작되기 전에 실행한다. 그래서 어떤 요청이나 동기화도 관리 대상이 되기 전의 레거시
// 글을 보지 않는다. 실패하면 애플리케이션이 시작되지 않는다.
class LegacyPostMigrationInitializer(
    private val migration: MigrateLegacyPostsUseCase,
) : SmartInitializingSingleton {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        val result = migration.migrate()
        if (result.migrated > 0) {
            logger.info("Brought {} legacy posts under management; {} stay public", result.migrated, result.madePublic)
        }
    }
}
