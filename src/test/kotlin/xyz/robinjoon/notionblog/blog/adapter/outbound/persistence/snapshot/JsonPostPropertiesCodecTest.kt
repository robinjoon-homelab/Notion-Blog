package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.net.URI
import java.time.Instant

class JsonPostPropertiesCodecTest {
    private val codec = JsonPostPropertiesCodec()

    @Test
    fun `round trips tags in order and both cover kinds with the logical media JSON`() {
        val hosted = MediaSource.SourceHosted(URI("https://files.example.com/c.png?sig=1"), Instant.parse("2026-10-16T01:00:00Z"))
        val external = MediaSource.External(URI("https://example.com/c.png"))

        assertThat(codec.decodeTags(codec.encodeTags(listOf("b", "a")))).containsExactly("b", "a")
        assertThat(codec.decodeCover(codec.encodeCover(hosted))).isEqualTo(hosted)
        assertThat(codec.decodeCover(codec.encodeCover(external))).isEqualTo(external)
        assertThat(codec.encodeCover(null)).isNull()
        assertThat(codec.encodeCover(hosted)).contains("\"kind\":\"source_hosted\"").doesNotContain("xyz.")
    }

    @Test
    fun `rejects malformed tag and cover JSON`() {
        assertThatIllegalArgumentException().isThrownBy { codec.decodeTags("{}") }
        assertThatIllegalArgumentException().isThrownBy { codec.decodeTags("[1]") }
        assertThatIllegalArgumentException().isThrownBy { codec.decodeCover("[]") }
        assertThatIllegalArgumentException().isThrownBy { codec.decodeTags("not json") }
    }
}
