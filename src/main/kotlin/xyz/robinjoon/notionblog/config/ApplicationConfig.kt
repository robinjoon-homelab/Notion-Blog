package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import java.time.Clock
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotionProperties::class, BlogProperties::class)
internal class ApplicationConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun refreshPolicy(properties: BlogProperties): RefreshPolicy =
        RefreshPolicy(
            successInterval = properties.synchronization.successInterval,
            initialFailureDelay = properties.synchronization.initialFailureDelay,
            maximumFailureDelay = properties.synchronization.maximumFailureDelay,
        )

    @Bean
    fun postIdFactory(): () -> PostId = { PostId(UUID.randomUUID()) }

    @Bean
    fun postRevisionIdFactory(): () -> PostRevisionId = { PostRevisionId(UUID.randomUUID()) }

    @Bean
    fun publicationIdFactory(): () -> PublicationId = { PublicationId(UUID.randomUUID()) }

    @Bean
    fun defaultPresentationProfileKey(properties: BlogProperties): PresentationProfileKey =
        PresentationProfileKey(properties.presentation.defaultProfileKey)
}
