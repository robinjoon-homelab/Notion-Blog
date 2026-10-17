package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.home

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedHomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database

class ExposedHomeConfigurationRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedHomeConfigurationRepository()

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `nothing is stored until the first save so the caller can use defaults`() {
        database.inTransaction {
            assertThat(repository.find()).isNull()
        }
    }

    @Test
    fun `a saved configuration round trips and a later save replaces the only one`() {
        val first = HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.OLDEST_FIRST, 25, HomePostDetails(false, true, false))
        val second = HomeConfiguration.create(HomeLayout.THUMBNAIL_LIST, HomePostOrder.NEWEST_FIRST, 50, HomePostDetails())

        database.inTransaction {
            repository.save(first)
            assertThat(repository.find()).isEqualTo(first)

            repository.save(second)
            assertThat(repository.find()).isEqualTo(second)
        }
    }
}
