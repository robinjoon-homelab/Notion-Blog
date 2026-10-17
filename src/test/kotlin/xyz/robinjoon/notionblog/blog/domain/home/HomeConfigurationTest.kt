package xyz.robinjoon.notionblog.blog.domain.home

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class HomeConfigurationTest {
    @Test
    fun `defaults keep the existing newest first list of ten posts with every detail shown`() {
        val defaults = HomeConfiguration.defaults()

        assertThat(defaults.layout).isEqualTo(HomeLayout.LIST)
        assertThat(defaults.order).isEqualTo(HomePostOrder.NEWEST_FIRST)
        assertThat(defaults.pageSize).isEqualTo(10)
        assertThat(defaults.details).isEqualTo(HomePostDetails(showSummary = true, showDate = true, showCategories = true))
    }

    @Test
    fun `posts per page must stay between one and fifty`() {
        val details = HomePostDetails(showSummary = false, showDate = true, showCategories = false)

        assertThat(HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.OLDEST_FIRST, 1, details).pageSize).isEqualTo(1)
        assertThat(HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.OLDEST_FIRST, 50, details).pageSize).isEqualTo(50)
        assertThatIllegalArgumentException()
            .isThrownBy { HomeConfiguration.create(HomeLayout.CARDS, HomePostOrder.OLDEST_FIRST, 0, details) }
        assertThatIllegalArgumentException()
            .isThrownBy { HomeConfiguration.restore(HomeLayout.LIST, HomePostOrder.NEWEST_FIRST, 51, details) }
    }
}
