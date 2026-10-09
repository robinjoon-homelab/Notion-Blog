package xyz.robinjoon.notionblog.adapter.outbound.notion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionFailureTranslator
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException

class NotionFailureTranslatorTest {
    private val translator = NotionFailureTranslator()

    @Test
    fun `classifies Notion HTTP failures into source neutral exceptions`() {
        assertThat(translator.httpFailure(429)).isInstanceOf(RetryableSourceException::class.java)
        assertThat(translator.httpFailure(503)).isInstanceOf(RetryableSourceException::class.java)
        assertThat(translator.httpFailure(401)).isInstanceOf(SourceAuthenticationException::class.java)
        assertThat(translator.httpFailure(403)).isInstanceOf(SourceAuthenticationException::class.java)
        assertThat(translator.httpFailure(404)).isInstanceOf(SourceAccessException::class.java)
        assertThat(translator.httpFailure(400)).isInstanceOf(SourceConfigurationException::class.java)
    }
}
