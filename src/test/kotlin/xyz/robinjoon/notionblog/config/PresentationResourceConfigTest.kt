package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

// 앱 컨텍스트 없이 리소스 핸들러 등록만 확인한다. 기본 정적 핸들러와 같이 캐시 헤더를 따로 붙이지 않는다.
class PresentationResourceConfigTest {
    private val context =
        AnnotationConfigWebApplicationContext().apply {
            servletContext = MockServletContext()
            register(WebMvcSupport::class.java, PresentationResourceConfig::class.java)
            refresh()
        }
    private val mockMvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).build()

    @AfterEach
    fun close() = context.close()

    @Test
    fun `serves a stylesheet at its public path with its bytes and content type`() {
        val result = mockMvc.perform(get("/presentation/notion/v1/notion.css")).andReturn().response

        assertThat(result.status).isEqualTo(200)
        assertThat(result.contentType).isEqualTo("text/css")
        assertThat(result.contentAsByteArray).isEqualTo(ClassPathResource("presentation/notion/v1/notion.css").contentAsByteArray)
        assertThat(result.getHeader("Cache-Control")).isNull()
    }

    @Test
    fun `serves a script at its public path with a JavaScript content type`() {
        val result = mockMvc.perform(get("/presentation/notion/database/v2/notion-database.js")).andReturn().response

        assertThat(result.status).isEqualTo(200)
        assertThat(result.contentType).isIn("text/javascript", "application/javascript")
    }

    @Test
    fun `does not serve files outside the presentation directory`() {
        assertThat(
            mockMvc
                .perform(get("/presentation/../application.yml"))
                .andReturn()
                .response.status,
        ).isEqualTo(404)
        assertThat(
            mockMvc
                .perform(get("/presentation/notion/missing.css"))
                .andReturn()
                .response.status,
        ).isEqualTo(404)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class WebMvcSupport
}
