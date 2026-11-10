package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest
import java.util.Base64

// 저장된 표현 프로필이 가리키는 자산은 바이트가 바뀌면 SRI 검증에 실패하므로 설정의 integrity와 실제 파일을 대조한다.
class PresentationAssetIntegrityTest {
    private val assets: List<BlogProperties.Asset> = bindAssets()

    @Test
    fun `every presentation asset in application yml resolves to a classpath file whose sha384 matches its integrity`() {
        assertThat(assets).isNotEmpty

        assets.forEach { asset ->
            val resource = ClassPathResource(classpathLocation(asset.publicPath))
            assertThat(resource.exists()).describedAs("%s resolves to %s", asset.publicPath, resource.path).isTrue()
            val digest = MessageDigest.getInstance("SHA-384").digest(resource.contentAsByteArray)
            assertThat(asset.integrity)
                .describedAs("integrity of %s", asset.publicPath)
                .isEqualTo("sha384-" + Base64.getEncoder().encodeToString(digest))
        }
    }

    private fun classpathLocation(publicPath: String): String =
        when {
            publicPath.startsWith("/presentation/") -> publicPath.removePrefix("/")
            publicPath.startsWith("/webjars/") -> "META-INF/resources$publicPath"
            else -> error("Unexpected presentation asset path $publicPath")
        }

    private fun bindAssets(): List<BlogProperties.Asset> {
        val sources = MutablePropertySources()
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).forEach(sources::addLast)
        return Binder(ConfigurationPropertySources.from(sources))
            .bind("blog.presentation", BlogProperties.Presentation::class.java)
            .get()
            .assets
    }
}
