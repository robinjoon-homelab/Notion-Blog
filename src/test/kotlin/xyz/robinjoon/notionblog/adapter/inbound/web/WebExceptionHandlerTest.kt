package xyz.robinjoon.notionblog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class WebExceptionHandlerTest {
    private val handler = WebExceptionHandler()

    @Test
    fun `handles only malformed post id as not found`() {
        val result = handler.handle()

        assertThat(result.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(result.viewName).isEqualTo("error/blog-not-found")
        assertThat(result.model).doesNotContainKey("exception")
    }
}
