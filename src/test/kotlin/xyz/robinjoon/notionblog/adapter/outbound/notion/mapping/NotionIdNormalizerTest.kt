package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException

class NotionIdNormalizerTest {
    @Test
    fun `normalizes hyphenated and uppercase Notion page ids to lowercase hexadecimal`() {
        assertThat(NotionIdNormalizer.normalize("A0B1C2D3-E4F5-6789-ABCD-EF0123456789"))
            .isEqualTo("a0b1c2d3e4f56789abcdef0123456789")
        assertThat(NotionIdNormalizer.normalize("A0B1C2D3E4F56789ABCDEF0123456789"))
            .isEqualTo("a0b1c2d3e4f56789abcdef0123456789")
    }

    @Test
    fun `rejects values that are not Notion page ids`() {
        assertThatThrownBy { NotionIdNormalizer.normalize("child-page") }
            .isInstanceOf(SourceMappingException::class.java)
    }
}
