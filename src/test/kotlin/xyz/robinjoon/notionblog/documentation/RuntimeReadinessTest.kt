package xyz.robinjoon.notionblog.documentation

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "notion.token=test-token",
        "notion.settings-data-source-id=test-settings",
        "notion.source-id=notion",
        "blog.synchronization.enabled=false",
    ],
)
@Import(RuntimeReadinessTest.ProbeConfiguration::class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RuntimeReadinessTest {
    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var probe: RequestThreadProbe

    @Autowired
    private lateinit var posts: PostSource

    @Test
    fun `real readiness HTTP requests run on virtual threads`() {
        val response = requestReadiness()

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"UP\"")
        assertThat(probe.observedVirtualThread.get(10, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `Notion service failure does not make readiness fail`() {
        val reference = SourceDocumentRef(SourceId("notion"), "00000000-0000-0000-0000-000000000001")
        assertThatThrownBy { posts.fetch(reference) }.isInstanceOf(RetryableSourceException::class.java)

        val response = requestReadiness()

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"UP\"")
    }

    private fun requestReadiness(): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/actuator/health/readiness"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        return HttpClient.newHttpClient().use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ProbeConfiguration {
        @Bean
        fun requestThreadProbe() = RequestThreadProbe()
    }

    class RequestThreadProbe : Filter {
        val observedVirtualThread = CompletableFuture<Boolean>()

        override fun doFilter(
            request: ServletRequest,
            response: ServletResponse,
            chain: FilterChain,
        ) {
            observedVirtualThread.complete(Thread.currentThread().isVirtual)
            chain.doFilter(request, response)
        }
    }

    companion object {
        private val notion =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
                    }
                start()
            }

        @Container
        @JvmStatic
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun infrastructureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", container::getJdbcUrl)
            registry.add("spring.datasource.username", container::getUsername)
            registry.add("spring.datasource.password", container::getPassword)
            registry.add("notion.base-url") { notion.url("/v1").toString().trimEnd('/') }
        }

        @AfterAll
        @JvmStatic
        fun stopNotionServer() {
            notion.shutdown()
        }
    }
}
