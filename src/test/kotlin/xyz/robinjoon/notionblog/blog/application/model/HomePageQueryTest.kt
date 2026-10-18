package xyz.robinjoon.notionblog.blog.application.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class HomePageQueryTest {
    @Test
    fun `no search text and trimmed search text up to the limit are accepted`() {
        val longest = "가".repeat(HomePageQuery.MAX_SEARCH_LENGTH)

        assertThat(HomePageQuery(1).search).isNull()
        assertThat(HomePageQuery(1, search = longest).search).isEqualTo(longest)
    }

    @Test
    fun `blank, untrimmed or too long search text is rejected`() {
        assertThatIllegalArgumentException().isThrownBy { HomePageQuery(1, search = "") }
        assertThatIllegalArgumentException().isThrownBy { HomePageQuery(1, search = " kotlin") }
        assertThatIllegalArgumentException().isThrownBy { HomePageQuery(1, search = "a".repeat(HomePageQuery.MAX_SEARCH_LENGTH + 1)) }
    }
}
