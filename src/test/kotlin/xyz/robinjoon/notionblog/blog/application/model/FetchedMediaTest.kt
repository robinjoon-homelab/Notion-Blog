package xyz.robinjoon.notionblog.blog.application.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey

class FetchedMediaTest {
    @Test
    fun `does not share its bytes with the caller that created or read it`() {
        val original = byteArrayOf(1, 2, 3)
        val media = fetched(bytes = original)

        original[0] = 9
        media.bytes[1] = 9

        assertThat(media.bytes).containsExactly(1, 2, 3)
        assertThat(media.size).isEqualTo(3)
    }

    @Test
    fun `normalizes the content type case and surrounding spaces`() {
        assertThat(fetched(contentType = " Image/PNG ").contentType).isEqualTo("image/png")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "image", "image/png; charset=binary", "image/ png", "<script>/x"])
    fun `rejects a content type that is not a bare type and subtype`(contentType: String) {
        assertThatIllegalArgumentException().isThrownBy { fetched(contentType = contentType) }
    }

    @Test
    fun `rejects empty bytes`() {
        assertThatIllegalArgumentException().isThrownBy { fetched(bytes = ByteArray(0)) }
    }

    private fun fetched(
        contentType: String = "image/png",
        bytes: ByteArray = byteArrayOf(1),
    ): FetchedMedia =
        FetchedMedia(
            sourceKey = SourceMediaKey("https://files.example/a.png"),
            mediaKey = MediaKey("a".repeat(64)),
            contentType = contentType,
            bytes = bytes,
        )
}
