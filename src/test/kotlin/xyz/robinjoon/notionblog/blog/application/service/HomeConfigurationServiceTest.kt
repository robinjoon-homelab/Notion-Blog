package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder

class HomeConfigurationServiceTest {
    private val configurations = mockk<HomeConfigurationRepository>()
    private val service = HomeConfigurationService(configurations)

    @Test
    fun `the home keeps the existing list until an administrator saves settings`() {
        every { configurations.find() } returns null

        assertThat(service.get()).isEqualTo(HomeConfiguration.defaults())
    }

    @Test
    fun `saved settings are returned as stored`() {
        val saved = HomeConfiguration.restore(HomeLayout.CARDS, HomePostOrder.OLDEST_FIRST, 24, HomePostDetails(showDate = false))
        every { configurations.find() } returns saved

        assertThat(service.get()).isEqualTo(saved)
    }
}
