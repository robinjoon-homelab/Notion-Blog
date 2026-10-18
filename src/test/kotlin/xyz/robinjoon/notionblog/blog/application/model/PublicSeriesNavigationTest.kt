package xyz.robinjoon.notionblog.blog.application.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import java.util.UUID

class PublicSeriesNavigationTest {
    private val seriesId = SeriesId(UUID.fromString("00000000-0000-0000-0001-000000000001"))
    private val links = listOf(link(1), link(2), link(3))

    @Test
    fun `the first post has only a next post`() {
        val navigation = PublicSeriesNavigation(seriesId, "DDD", links, post(1))

        assertThat(navigation.currentIndex).isZero()
        assertThat(navigation.previous).isNull()
        assertThat(navigation.next).isEqualTo(link(2))
    }

    @Test
    fun `a middle post links both neighbours among the listed public posts`() {
        val navigation = PublicSeriesNavigation(seriesId, "DDD", links, post(2))

        assertThat(navigation.currentIndex).isEqualTo(1)
        assertThat(navigation.previous).isEqualTo(link(1))
        assertThat(navigation.next).isEqualTo(link(3))
    }

    @Test
    fun `the last post and a single-post series have no next post`() {
        val last = PublicSeriesNavigation(seriesId, "DDD", links, post(3))
        val single = PublicSeriesNavigation(seriesId, "DDD", listOf(link(1)), post(1))

        assertThat(last.previous).isEqualTo(link(2))
        assertThat(last.next).isNull()
        assertThat(single.previous).isNull()
        assertThat(single.next).isNull()
    }

    @Test
    fun `the current post must be one of the listed posts`() {
        assertThatIllegalArgumentException().isThrownBy { PublicSeriesNavigation(seriesId, "DDD", links, post(9)) }
    }

    private fun link(label: Int): SeriesPostLink = SeriesPostLink(post(label), "Post $label")

    private fun post(label: Int): PostId = PostId(UUID.fromString("00000000-0000-0000-0000-%012d".format(label)))
}
