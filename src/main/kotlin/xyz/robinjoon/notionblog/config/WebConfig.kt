package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.JsonLdWriter
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.LlmsTxtRenderer
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewAssembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostSummaryExtractor
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PublicBlogUrls
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.RssFeedRenderer
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.SitemapRenderer
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebSecurityHeadersFilter
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class WebConfig {
    @Bean
    fun publicBlogUrls(properties: BlogProperties): PublicBlogUrls = PublicBlogUrls(properties.publicBaseUri)

    @Bean
    fun postSummaryExtractor(): PostSummaryExtractor = PostSummaryExtractor()

    @Bean
    fun rssFeedRenderer(summaryExtractor: PostSummaryExtractor): RssFeedRenderer = RssFeedRenderer(summaryExtractor)

    @Bean
    fun sitemapRenderer(): SitemapRenderer = SitemapRenderer()

    @Bean
    fun llmsTxtRenderer(summaryExtractor: PostSummaryExtractor): LlmsTxtRenderer = LlmsTxtRenderer(summaryExtractor)

    @Bean
    fun jsonLdWriter(): JsonLdWriter = JsonLdWriter()

    @Bean
    fun postPageViewAssembler(
        clock: Clock,
        urls: PublicBlogUrls,
        summaryExtractor: PostSummaryExtractor,
        jsonLdWriter: JsonLdWriter,
    ): PostPageViewAssembler = PostPageViewAssembler(clock, urls, summaryExtractor, jsonLdWriter)

    @Bean
    fun webSecurityHeadersFilter(): WebSecurityHeadersFilter = WebSecurityHeadersFilter()
}
