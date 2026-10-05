package xyz.robinjoon.notionblog.adapter.input.web

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.post.PostId
import java.net.URI
import java.util.UUID

class PublicBlogUrlsTest {
    private val postId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000123"))

    @Test
    fun `builds root feed and post URLs from the configured origin`() {
        val urls = PublicBlogUrls(URI("https://blog.example:8443/"))

        assertThat(urls.rootUrl).isEqualTo("https://blog.example:8443/")
        assertThat(urls.feedUrl).isEqualTo("https://blog.example:8443/feed.xml")
        assertThat(urls.postUrl(postId)).isEqualTo("https://blog.example:8443/posts/00000000-0000-0000-0000-000000000123")
    }

    @Test
    fun `preserves the IPv6 origin when creating public URLs`() {
        val urls = PublicBlogUrls(URI("http://[::1]:8080/"))

        assertThat(urls.rootUrl).isEqualTo("http://[::1]:8080/")
        assertThat(urls.feedUrl).isEqualTo("http://[::1]:8080/feed.xml")
        assertThat(urls.postUrl(postId)).isEqualTo("http://[::1]:8080/posts/00000000-0000-0000-0000-000000000123")
    }

    @Test
    fun `omits discovery URLs and prevents post URL generation without a configured origin`() {
        val urls = PublicBlogUrls(null)

        assertThat(urls.rootUrl).isNull()
        assertThat(urls.feedUrl).isNull()
        assertThatIllegalArgumentException().isThrownBy { urls.postUrl(postId) }
    }
}
