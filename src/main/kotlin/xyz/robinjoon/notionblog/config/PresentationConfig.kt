package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.adapter.outbound.presentation.ClasspathPresentationAssetCatalog
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef

@Configuration(proxyBeanMethods = false)
internal class PresentationConfig {
    @Bean
    fun presentationAssetCatalog(properties: BlogProperties): PresentationAssetCatalog {
        val references =
            properties.presentation.assets.associate { asset ->
                val reference = PresentationAssetRef(asset.key, asset.version, asset.integrity)
                reference to PresentationAssetDescriptor(asset.publicPath, asset.mediaType, asset.integrity)
            }
        val currentReferences =
            properties.presentation.assets
                .filter(BlogProperties.Asset::current)
                .associate { asset -> asset.key to PresentationAssetRef(asset.key, asset.version, asset.integrity) }
        return ClasspathPresentationAssetCatalog(references, currentReferences)
    }
}
