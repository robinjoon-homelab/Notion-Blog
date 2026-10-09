package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest
import java.util.Base64

class DefaultPresentationAssetsContractTest {
    @Test
    fun `default presentation assets are pinned classpath resources with matching integrity`() {
        val properties =
            checkNotNull(
                YamlPropertiesFactoryBean()
                    .apply {
                        setResources(ClassPathResource("application.yml"))
                    }.getObject(),
            )
        val assets =
            generateSequence(0) { it + 1 }
                .map { index ->
                    val prefix = "blog.presentation.assets[$index]"
                    val key = properties.getProperty("$prefix.key") ?: return@map null
                    ConfiguredAsset(
                        key = key,
                        version = properties.getProperty("$prefix.version").toLong(),
                        integrity = properties.getProperty("$prefix.integrity"),
                        publicPath = properties.getProperty("$prefix.public-path"),
                        mediaType = properties.getProperty("$prefix.media-type"),
                        current = properties.getProperty("$prefix.current").toBoolean(),
                    )
                }.takeWhile { it != null }
                .filterNotNull()
                .toList()

        assertThat(assets.map { it.key to it.version }).containsExactly(
            "notion-core" to 1L,
            "katex-styles" to 1L,
            "notion-enhancements" to 1L,
            "notion-database" to 1L,
            "notion-database" to 2L,
            "notion-database" to 3L,
            "katex-runtime" to 1L,
            "notion-tabs" to 1L,
            "notion-math" to 1L,
            "notion-database-behavior" to 1L,
        )
        assertThat(assets.filterNot(ConfiguredAsset::current).map { it.key to it.version })
            .containsExactly("notion-database" to 1L, "notion-database" to 2L)
        assertThat(assets.filter(ConfiguredAsset::current).groupBy(ConfiguredAsset::key).values)
            .allSatisfy { versions -> assertThat(versions).hasSize(1) }
        assertThat(assets.single { it.key == "notion-database" && it.current }.publicPath)
            .isEqualTo("/presentation/notion/database/v3/notion-database.css")
        assertThat(assets.single { it.key == "notion-database-behavior" }.publicPath)
            .isEqualTo("/presentation/notion/database/v2/notion-database.js")
        assertThat(assets).allSatisfy { asset ->
            assertThat(asset.version).isPositive()
            assertThat(asset.mediaType).isIn("text/css", "application/javascript")
            val resource = classpathResource(asset.publicPath)
            assertThat(resource.exists()).describedAs(asset.publicPath).isTrue()
            assertThat(asset.integrity).isEqualTo(resource.sha384Integrity())
        }
    }

    private fun classpathResource(publicPath: String): ClassPathResource {
        val prefix = if (publicPath.startsWith("/webjars/")) "META-INF/resources" else "static"
        return ClassPathResource(prefix + publicPath)
    }

    private fun ClassPathResource.sha384Integrity(): String {
        val digest = inputStream.use { stream -> MessageDigest.getInstance("SHA-384").digest(stream.readAllBytes()) }
        return "sha384-${Base64.getEncoder().encodeToString(digest)}"
    }

    private data class ConfiguredAsset(
        val key: String,
        val version: Long,
        val integrity: String,
        val publicPath: String,
        val mediaType: String,
        val current: Boolean,
    )
}
