package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.adapter.outbound.media.HttpMediaGateway
import xyz.robinjoon.notionblog.blog.adapter.outbound.media.MediaFetchLimits
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaGateway

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties::class)
internal class MediaConfig {
    // 주소 정책과 HTTP 클라이언트는 기본값만 쓴다. 전용 클라이언트는 애플리케이션 종료 때 close로 닫힌다.
    @Bean
    fun mediaGateway(properties: MediaProperties): MediaGateway =
        HttpMediaGateway(
            MediaFetchLimits(
                maxBytes = properties.maxBytes,
                requestTimeout = properties.requestTimeout,
                maxRedirects = properties.maxRedirects,
            ),
        )
}
