package xyz.robinjoon.notionblog.blog.domain.series

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import java.util.UUID

class SeriesTest {
    private val id = SeriesId(UUID.fromString("00000000-0000-0000-0001-000000000001"))

    @Test
    fun `a new series trims its title and description and starts without posts`() {
        val series = Series.create(id, "  DDD 적용기 ", "  순서대로 읽는 글 ")

        assertThat(series).isEqualTo(Series.restore(id, "DDD 적용기", "순서대로 읽는 글", emptyList()))
    }

    @Test
    fun `the longest allowed title and description are accepted`() {
        val title = "가".repeat(Series.MAX_TITLE_LENGTH)
        val description = "나".repeat(Series.MAX_DESCRIPTION_LENGTH)

        assertThat(Series.create(id, " $title ", " $description ").title).isEqualTo(title)
    }

    @Test
    fun `a blank or too long title, a too long description and a repeated post are rejected`() {
        assertThatIllegalArgumentException().isThrownBy { Series.create(id, "   ", "") }
        assertThatIllegalArgumentException().isThrownBy { Series.create(id, "a".repeat(Series.MAX_TITLE_LENGTH + 1), "") }
        assertThatIllegalArgumentException().isThrownBy { Series.create(id, "DDD", "a".repeat(Series.MAX_DESCRIPTION_LENGTH + 1)) }
        assertThatIllegalArgumentException().isThrownBy { Series.restore(id, "DDD", "", listOf(post(1), post(1))) }
    }

    @Test
    fun `updating replaces the order and is not affected by later changes to the caller's list`() {
        val order = arrayListOf(post(2), post(1))
        val updated = Series.create(id, "DDD", "").update(" Kotlin ", "", order)
        order.add(post(3))

        assertThat(updated).isEqualTo(Series.restore(id, "Kotlin", "", listOf(post(2), post(1))))
    }

    @Test
    fun `appending puts a new post last and keeps the position of a post already in the series`() {
        val series = Series.restore(id, "DDD", "", listOf(post(1), post(2)))

        assertThat(series.append(post(3)).postIds).containsExactly(post(1), post(2), post(3))
        assertThat(series.append(post(1)).postIds).containsExactly(post(1), post(2))
    }

    @Test
    fun `removing a post keeps the order of the others`() {
        val series = Series.restore(id, "DDD", "", listOf(post(1), post(2), post(3)))

        assertThat(series.remove(post(2)).postIds).containsExactly(post(1), post(3))
        assertThat(series.remove(post(9)).postIds).containsExactly(post(1), post(2), post(3))
    }

    private fun post(label: Int): PostId = PostId(UUID.fromString("00000000-0000-0000-0000-%012d".format(label)))
}
