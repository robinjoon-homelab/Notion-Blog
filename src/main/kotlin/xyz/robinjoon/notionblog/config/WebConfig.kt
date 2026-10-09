package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.adapter.inbound.web.PostPageViewAssembler
import xyz.robinjoon.notionblog.adapter.inbound.web.PublicBlogUrls
import xyz.robinjoon.notionblog.adapter.inbound.web.RssFeedRenderer
import xyz.robinjoon.notionblog.adapter.inbound.web.RssSummaryExtractor
import xyz.robinjoon.notionblog.adapter.inbound.web.WebSecurityHeadersFilter
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class WebConfig {
    @Bean
    fun publicBlogUrls(properties: BlogProperties): PublicBlogUrls = PublicBlogUrls(properties.publicBaseUri)

    @Bean
    fun rssSummaryExtractor(): RssSummaryExtractor = RssSummaryExtractor()

    @Bean
    fun rssFeedRenderer(summaryExtractor: RssSummaryExtractor): RssFeedRenderer = RssFeedRenderer(summaryExtractor)

    @Bean
    fun postPageViewAssembler(
        clock: Clock,
        urls: PublicBlogUrls,
    ): PostPageViewAssembler = PostPageViewAssembler(clock, urls.feedUrl)

    @Bean
    fun webSecurityHeadersFilter(): WebSecurityHeadersFilter = WebSecurityHeadersFilter()
}
