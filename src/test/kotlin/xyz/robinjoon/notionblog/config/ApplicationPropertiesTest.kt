package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Duration

// Spring binds these properties through their constructors, so each validation is checked by constructing the
// properties directly. Binding itself and application.yml defaults are framework configuration.
class ApplicationPropertiesTest {
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t\n"])
    fun `leaves RSS disabled when the public base URL is blank`(value: String) {
        assertThat(BlogProperties(publicBaseUrl = value).publicBaseUri).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "https://blog.example, https://blog.example/",
        "https://blog.example/, https://blog.example/",
        "HTTPS://Blog.Example, HTTPS://Blog.Example/",
        "http://localhost:8080, http://localhost:8080/",
        "http://localhost:1/, http://localhost:1/",
        "https://blog.example:65535, https://blog.example:65535/",
        "http://[::1]:8080/, http://[::1]:8080/",
        "https://[2001:db8::1], https://[2001:db8::1]/",
    )
    fun `accepts an HTTP origin and normalizes its root path`(
        value: String,
        normalized: String,
    ) {
        assertThat(BlogProperties(publicBaseUrl = value).publicBaseUri).isEqualTo(URI(normalized))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "blog.example", "//blog.example", "https:blog.example", "https://", "ftp://blog.example",
            "https://user@blog.example", "https://@blog.example", "https://blog.example?", "https://blog.example?a=1",
            "https://blog.example#", "https://blog.example#section", "https://blog.example/blog", "https://blog.example//",
            "https://blog.example/.", "https://blog.example/%2F", "https://:8080", "https://blog.example:",
            "https://blog.example:0", "https://blog.example:-1", "https://blog.example:abc", "https://blog.example:65536",
            "http://[::1]:", "http://[::1]:0", "https://blog.example/ invalid",
        ],
    )
    fun `rejects a public base URL that is not a valid HTTP origin`(value: String) {
        assertThatIllegalArgumentException().isThrownBy { BlogProperties(publicBaseUrl = value) }
    }

    @Test
    fun `rejects a missing Notion token`() {
        assertThatIllegalArgumentException().isThrownBy { notion().copy(token = " ") }
    }

    @Test
    fun `rejects an unsupported Notion API version`() {
        assertThatIllegalArgumentException().isThrownBy { notion().copy(apiVersion = "2025-09-03") }
        assertThatCode { notion() }.doesNotThrowAnyException()
    }

    @Test
    fun `rejects invalid synchronization and Notion collection limits`() {
        assertThatIllegalArgumentException().isThrownBy { notion().copy(maxBlockDepth = 0) }
        assertThatIllegalArgumentException().isThrownBy { notion().copy(maxBlockCount = 0) }
        assertThatIllegalArgumentException().isThrownBy { notion().copy(requestTimeout = Duration.ZERO) }
        assertThatIllegalArgumentException().isThrownBy { BlogProperties.Synchronization(intervalMs = 0) }
        assertThatIllegalArgumentException().isThrownBy { BlogProperties.Synchronization(dueBatchSize = 0) }
        assertThatIllegalArgumentException().isThrownBy { BlogProperties.Synchronization(successInterval = Duration.ZERO) }
        assertThatIllegalArgumentException().isThrownBy {
            BlogProperties.Synchronization(initialFailureDelay = Duration.ofSeconds(5), maximumFailureDelay = Duration.ofSeconds(1))
        }
    }

    @Test
    fun `rejects duplicate exact assets and multiple current assets for one key`() {
        assertThatIllegalArgumentException().isThrownBy {
            presentation(core(), asset(version = 1, integrity = "sha384-core", publicPath = "/presentation/notion/v1/duplicate.css"))
        }
        assertThatIllegalArgumentException().isThrownBy {
            presentation(core(), asset(version = 2, integrity = "sha384-core-v2", current = true))
        }
    }

    @Test
    fun `rejects assets that reuse a key and version with a different integrity`() {
        assertThatIllegalArgumentException().isThrownBy {
            presentation(
                core(),
                asset(version = 1, integrity = "sha384-core-replaced", publicPath = "/presentation/notion/v1/replaced.css"),
            )
        }
    }

    @Test
    fun `allows different versions for the same asset key when only one is current`() {
        assertThatCode {
            presentation(core(), asset(version = 2, integrity = "sha384-core-v2"))
        }.doesNotThrowAnyException()
    }

    private fun notion() =
        NotionProperties(
            token = "test-token",
            settingsDataSourceId = "settings-data-source",
            apiVersion = "2026-03-11",
            baseUrl = "https://api.notion.test/v1",
            requestTimeout = Duration.ofSeconds(4),
            collectionTimeout = Duration.ofSeconds(20),
            maxBlockDepth = 12,
            maxBlockCount = 4_000,
        )

    private fun presentation(vararg assets: BlogProperties.Asset) = BlogProperties.Presentation(assets = assets.toList())

    private fun core() = asset(version = 1, integrity = "sha384-core", current = true)

    private fun asset(
        version: Long,
        integrity: String,
        publicPath: String = "/presentation/notion/v$version/notion.css",
        current: Boolean = false,
    ) = BlogProperties.Asset(
        key = "notion-core",
        version = version,
        integrity = integrity,
        publicPath = publicPath,
        mediaType = "text/css",
        current = current,
    )
}
