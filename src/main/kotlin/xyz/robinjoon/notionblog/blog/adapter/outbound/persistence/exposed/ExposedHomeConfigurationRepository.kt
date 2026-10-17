package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.HomeConfigurationTable
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder

// 홈 설정은 앱 DB에만 있는 단일 행이다. 행이 없으면 호출자가 기본값을 쓴다.
class ExposedHomeConfigurationRepository : HomeConfigurationRepository {
    override fun find(): HomeConfiguration? =
        HomeConfigurationTable
            .selectAll()
            .singleOrNull()
            ?.toConfiguration()

    override fun save(configuration: HomeConfiguration) {
        HomeConfigurationTable.upsert(HomeConfigurationTable.homeId) {
            it[homeId] = HOME_ID
            it[layout] = configuration.layout.name
            it[postOrder] = configuration.order.name
            it[pageSize] = configuration.pageSize
            it[showSummary] = configuration.details.showSummary
            it[showDate] = configuration.details.showDate
            it[showCategories] = configuration.details.showCategories
        }
    }

    private fun ResultRow.toConfiguration(): HomeConfiguration =
        HomeConfiguration.restore(
            HomeLayout.valueOf(this[HomeConfigurationTable.layout]),
            HomePostOrder.valueOf(this[HomeConfigurationTable.postOrder]),
            this[HomeConfigurationTable.pageSize],
            HomePostDetails(
                showSummary = this[HomeConfigurationTable.showSummary],
                showDate = this[HomeConfigurationTable.showDate],
                showCategories = this[HomeConfigurationTable.showCategories],
            ),
        )

    private companion object {
        const val HOME_ID: Short = 1
    }
}
