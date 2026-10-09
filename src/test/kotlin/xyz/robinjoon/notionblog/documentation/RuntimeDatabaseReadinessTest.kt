package xyz.robinjoon.notionblog.documentation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "notion.token=test-token",
        "notion.settings-data-source-id=test-settings",
        "blog.synchronization.enabled=false",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=500",
    ],
)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RuntimeDatabaseReadinessTest {
    @LocalServerPort
    private var port = 0

    @Test
    fun `database outage removes readiness while liveness remains healthy`() {
        assertThat(requestHealth("readiness").statusCode()).isEqualTo(200)
        container.stop()

        val readiness = requestHealth("readiness")
        val liveness = requestHealth("liveness")

        assertThat(readiness.statusCode()).isEqualTo(503)
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"")
        assertThat(liveness.statusCode()).isEqualTo(200)
        assertThat(liveness.body()).contains("\"status\":\"UP\"")
    }

    private fun requestHealth(group: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/actuator/health/$group"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        return HttpClient.newHttpClient().use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }
    }

    companion object {
        @Container
        @JvmStatic
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", container::getJdbcUrl)
            registry.add("spring.datasource.username", container::getUsername)
            registry.add("spring.datasource.password", container::getPassword)
        }
    }
}
