package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.net.URI
import java.time.Duration

class ApplicationPropertiesTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `permits typed configuration without a public base URL`() {
        contextRunner.withPropertyValues(*validProperties()).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(BlogProperties::class.java).publicBaseUri).isNull()
        }
    }

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
    fun `binds an HTTP origin and normalizes its root path`(
        value: String,
        normalized: String,
    ) {
        contextRunner.withPropertyValues(*validProperties("blog.public-base-url=$value")).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(BlogProperties::class.java).publicBaseUri).isEqualTo(URI(normalized))
        }
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
        contextRunner.withPropertyValues(*validProperties("blog.public-base-url=$value")).run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `uses the existing public blog origin by default from application yaml`() {
        assertPublicOriginFromEnvironment(emptyMap(), URI("https://blog.homelab.robinjoon.xyz/"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    fun `explicitly blank environment values disable the default public origin`(value: String) {
        assertPublicOriginFromEnvironment(mapOf("BLOG_PUBLIC_BASE_URL" to value), null)
    }

    @Test
    fun `loads the public origin from BLOG_PUBLIC_BASE_URL through application yaml`() {
        assertPublicOriginFromEnvironment(
            mapOf("BLOG_PUBLIC_BASE_URL" to "https://environment.example:8443"),
            URI("https://environment.example:8443/"),
        )
    }

    private fun assertPublicOriginFromEnvironment(
        environment: Map<String, Any>,
        expected: URI?,
    ) {
        contextRunner
            .withPropertyValues(
                "spring.config.location=classpath:/application.yml",
                "notion.token=test-token",
                "notion.settings-data-source-id=settings-data-source",
            ).withInitializer { context ->
                context.environment.propertySources.replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        environment,
                    ),
                )
                ConfigDataApplicationContextInitializer().initialize(context)
            }.run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(BlogProperties::class.java).publicBaseUri)
                    .isEqualTo(expected)
            }
    }

    @Test
    fun `binds typed synchronization and deployed presentation asset settings`() {
        contextRunner
            .withPropertyValues(*validProperties())
            .run { context ->
                assertThat(context).hasNotFailed()

                val notion = context.getBean(NotionProperties::class.java)
                assertThat(notion.sourceId).isEqualTo("notion")
                assertThat(notion.apiVersion).isEqualTo("2026-03-11")
                assertThat(notion.maxBlockDepth).isEqualTo(12)
                assertThat(notion.maxBlockCount).isEqualTo(4_000)
                assertThat(notion.requestTimeout).isEqualTo(Duration.ofSeconds(4))

                val blog = context.getBean(BlogProperties::class.java)
                assertThat(blog.synchronization.enabled).isFalse()
                assertThat(blog.synchronization.intervalMs).isEqualTo(45_000)
                assertThat(blog.synchronization.dueBatchSize).isEqualTo(24)
                assertThat(blog.synchronization.successInterval).isEqualTo(Duration.ofMinutes(5))
                assertThat(blog.presentation.defaultProfileKey).isEqualTo("notion-default")
                val asset = blog.presentation.assets.single()
                assertThat(asset.key).isEqualTo("notion-core")
                assertThat(asset.version).isEqualTo(1)
                assertThat(asset.current).isTrue()
            }
    }

    @Test
    fun `uses the configured Notion source identifier default`() {
        contextRunner
            .withPropertyValues(*validProperties().filterNot { it.startsWith("notion.source-id=") }.toTypedArray())
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(NotionProperties::class.java).sourceId).isEqualTo("notion")
            }
    }

    @Test
    fun `uses safe Notion and synchronization defaults when only required secrets are supplied`() {
        contextRunner
            .withPropertyValues(
                "notion.token=test-token",
                "notion.settings-data-source-id=settings-data-source",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val notion = context.getBean(NotionProperties::class.java)
                assertThat(notion.apiVersion).isEqualTo("2026-03-11")
                assertThat(notion.maxBlockDepth).isEqualTo(32)
                assertThat(notion.maxBlockCount).isEqualTo(10_000)
                assertThat(context.getBean(BlogProperties::class.java).synchronization.dueBatchSize).isEqualTo(100)
            }
    }

    @Test
    fun `rejects a missing Notion token`() {
        contextRunner
            .withPropertyValues(*validProperties().filterNot { it.startsWith("notion.token=") }.toTypedArray())
            .run { context ->
                assertThat(context).hasFailed()
            }
    }

    @Test
    fun `rejects an unsupported Notion API version`() {
        contextRunner
            .withPropertyValues(*validProperties("notion.api-version=2025-09-03"))
            .run { context ->
                assertThat(context).hasFailed()
            }
    }

    @Test
    fun `rejects invalid synchronization and Notion collection limits`() {
        listOf(
            "notion.max-block-depth=0",
            "notion.max-block-count=0",
            "notion.request-timeout=0s",
            "blog.synchronization.interval-ms=0",
            "blog.synchronization.due-batch-size=0",
            "blog.synchronization.success-interval=0s",
            "blog.synchronization.maximum-failure-delay=1s",
        ).forEach { invalidProperty ->
            contextRunner
                .withPropertyValues(*validProperties(invalidProperty))
                .run { context -> assertThat(context).hasFailed() }
        }
    }

    @Test
    fun `rejects duplicate exact assets and multiple current assets for one key`() {
        val duplicate =
            arrayOf(
                "blog.presentation.assets[1].key=notion-core",
                "blog.presentation.assets[1].version=1",
                "blog.presentation.assets[1].integrity=sha384-core",
                "blog.presentation.assets[1].public-path=/presentation/notion/v1/duplicate.css",
                "blog.presentation.assets[1].media-type=text/css",
                "blog.presentation.assets[1].current=false",
            )
        contextRunner.withPropertyValues(*validProperties(*duplicate)).run { context ->
            assertThat(context).hasFailed()
        }

        val duplicateCurrent =
            arrayOf(
                "blog.presentation.assets[1].key=notion-core",
                "blog.presentation.assets[1].version=2",
                "blog.presentation.assets[1].integrity=sha384-core-v2",
                "blog.presentation.assets[1].public-path=/presentation/notion/v2/notion.css",
                "blog.presentation.assets[1].media-type=text/css",
                "blog.presentation.assets[1].current=true",
            )
        contextRunner.withPropertyValues(*validProperties(*duplicateCurrent)).run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `rejects assets that reuse a key and version with a different integrity`() {
        val duplicateLogicalIdentity =
            arrayOf(
                "blog.presentation.assets[1].key=notion-core",
                "blog.presentation.assets[1].version=1",
                "blog.presentation.assets[1].integrity=sha384-core-replaced",
                "blog.presentation.assets[1].public-path=/presentation/notion/v1/replaced.css",
                "blog.presentation.assets[1].media-type=text/css",
                "blog.presentation.assets[1].current=false",
            )

        contextRunner.withPropertyValues(*validProperties(*duplicateLogicalIdentity)).run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `allows different versions for the same asset key when only one is current`() {
        val olderVersion =
            arrayOf(
                "blog.presentation.assets[1].key=notion-core",
                "blog.presentation.assets[1].version=2",
                "blog.presentation.assets[1].integrity=sha384-core-v2",
                "blog.presentation.assets[1].public-path=/presentation/notion/v2/notion.css",
                "blog.presentation.assets[1].media-type=text/css",
                "blog.presentation.assets[1].current=false",
            )

        contextRunner.withPropertyValues(*validProperties(*olderVersion)).run { context ->
            assertThat(context).hasNotFailed()
        }
    }

    private fun validProperties(vararg overrides: String): Array<String> =
        arrayOf(
            "notion.token=test-token",
            "notion.settings-data-source-id=settings-data-source",
            "notion.api-version=2026-03-11",
            "notion.base-url=https://api.notion.test/v1",
            "notion.request-timeout=4s",
            "notion.collection-timeout=20s",
            "notion.max-block-depth=12",
            "notion.max-block-count=4000",
            "blog.synchronization.enabled=false",
            "blog.synchronization.interval-ms=45000",
            "blog.synchronization.due-batch-size=24",
            "blog.synchronization.success-interval=5m",
            "blog.synchronization.initial-failure-delay=5s",
            "blog.synchronization.maximum-failure-delay=1m",
            "blog.presentation.default-profile-key=notion-default",
            "blog.presentation.assets[0].key=notion-core",
            "blog.presentation.assets[0].version=1",
            "blog.presentation.assets[0].integrity=sha384-core",
            "blog.presentation.assets[0].public-path=/presentation/notion/v1/notion.css",
            "blog.presentation.assets[0].media-type=text/css",
            "blog.presentation.assets[0].current=true",
            *overrides,
        )

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotionProperties::class, BlogProperties::class)
    private class PropertiesConfiguration
}
