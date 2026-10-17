package xyz.robinjoon.notionblog.blog.domain.post.block.media

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI

class MediaKeysTest {
    @ParameterizedTest
    @CsvSource(
        "https://files.example/ws/a.png?X-Amz-Signature=secret#top, https://files.example/ws/a.png",
        "https://files.example/ws/a.png#top?not-a-query, https://files.example/ws/a.png",
        "https://files.example/ws/a.png, https://files.example/ws/a.png",
    )
    fun `uses the file URL without its signed query or fragment as the source key`(
        url: String,
        key: String,
    ) {
        assertThat(SourceMediaKey.of(URI(url)).value).isEqualTo(key)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"])
    fun `rejects a media key that is not a lowercase SHA-256 digest`(value: String) {
        assertThatIllegalArgumentException().isThrownBy { MediaKey(value) }
    }
}
