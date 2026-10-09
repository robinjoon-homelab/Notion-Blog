package xyz.robinjoon.notionblog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.servlet.DispatcherServlet
import xyz.robinjoon.notionblog.adapter.inbound.scheduling.SynchronizationScheduler
import xyz.robinjoon.notionblog.adapter.inbound.web.BlogController
import xyz.robinjoon.notionblog.adapter.inbound.web.PostPageViewAssembler
import xyz.robinjoon.notionblog.adapter.inbound.web.WebSecurityHeadersFilter
import xyz.robinjoon.notionblog.adapter.outbound.diagnostics.Slf4jSnapshotFailureReporter
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.source.NotionPostSource
import xyz.robinjoon.notionblog.adapter.outbound.notion.source.NotionSiteConfigurationSource
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedPostRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedPublicationRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedSiteConfigurationRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedSyncStateRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.adapter.outbound.presentation.ClasspathPresentationAssetCatalog
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.application.service.GetBlogPageService
import xyz.robinjoon.notionblog.application.service.GetPostFeedService
import xyz.robinjoon.notionblog.application.service.GetPublishedPostService
import xyz.robinjoon.notionblog.application.service.ResolvePostLinksService
import xyz.robinjoon.notionblog.application.service.SynchronizationQueryService
import xyz.robinjoon.notionblog.application.service.SynchronizePostService
import xyz.robinjoon.notionblog.application.service.SynchronizePublicationService
import xyz.robinjoon.notionblog.application.service.SynchronizeSiteConfigurationService
import java.time.Clock
import java.time.ZoneOffset

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:postgresql://localhost:1/notion_blog",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.flyway.enabled=false",
        "notion.token=test-token",
        "notion.settings-data-source-id=settings-data-source",
        "notion.api-version=2026-03-11",
        "notion.base-url=http://127.0.0.1:1/v1",
        "notion.request-timeout=1s",
        "notion.collection-timeout=2s",
        "notion.max-block-depth=16",
        "notion.max-block-count=1000",
        "blog.synchronization.enabled=false",
        "blog.synchronization.interval-ms=60000",
        "blog.synchronization.due-batch-size=16",
        "blog.synchronization.success-interval=5m",
        "blog.synchronization.initial-failure-delay=5s",
        "blog.synchronization.maximum-failure-delay=1m",
        "blog.presentation.default-profile-key=notion-default",
        "blog.presentation.assets[0].key=notion-core",
        "blog.presentation.assets[0].version=1",
        "blog.presentation.assets[0].integrity=sha384-V763UM2y9iSN6rUXr+H4a3GeowrAJqJ53QPBQoJQ9/W3UVee9kfeg7pO3tJJ7V/T",
        "blog.presentation.assets[0].public-path=/presentation/notion/v1/notion.css",
        "blog.presentation.assets[0].media-type=text/css",
        "blog.presentation.assets[0].current=true",
        "blog.presentation.assets[1].key=notion-tabs",
        "blog.presentation.assets[1].version=1",
        "blog.presentation.assets[1].integrity=sha384-VucbIMH0dIpFjnUI6nyjosBUX+cUDRo82zmVz+TihzIdd4C9WwKtpQ1i06jBFgUy",
        "blog.presentation.assets[1].public-path=/presentation/notion/v1/notion.js",
        "blog.presentation.assets[1].media-type=application/javascript",
        "blog.presentation.assets[1].current=true",
    ],
)
class ContextLoadTest(
    @Autowired private val applicationContext: ApplicationContext,
) {
    @Test
    fun `public use cases are assembled by configuration factory methods`() {
        val context = applicationContext as ConfigurableApplicationContext
        val useCases =
            listOf(
                GetBlogPageService::class.java,
                GetPostFeedService::class.java,
                SynchronizationQueryService::class.java,
                SynchronizeSiteConfigurationService::class.java,
                SynchronizePublicationService::class.java,
                SynchronizePostService::class.java,
            )

        useCases.forEach { type ->
            val beanName = context.getBeanNamesForType(type).single()
            val definition = context.beanFactory.getBeanDefinition(beanName)
            assertThat(definition.factoryMethodName).describedAs(type.simpleName).isNotBlank()
        }
        assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(WebSecurityHeadersFilter::class.java)).hasSize(1)
    }

    @Test
    fun `MVC context starts with the target bean graph without remote calls`() {
        assertThat(applicationContext.getBean(DispatcherServlet::class.java)).isNotNull
        val transactionManager = applicationContext.getBean(PlatformTransactionManager::class.java)
        assertThat(transactionManager).isNotInstanceOf(JdbcTransactionManager::class.java)
        assertThat(transactionManager.javaClass.name).contains("org.jetbrains.exposed")
        assertThat(applicationContext.getBean(HealthEndpoint::class.java)).isNotNull
        assertThat(applicationContext.getBean(Clock::class.java).zone).isEqualTo(ZoneOffset.UTC)

        assertThat(applicationContext.getBean(JsonBlockTreeSnapshotCodec::class.java)).isNotNull
        assertThat(applicationContext.getBean(ExposedPostRepository::class.java)).isNotNull
        assertThat(applicationContext.getBean(ExposedPublicationRepository::class.java)).isNotNull
        assertThat(applicationContext.getBean(ExposedSiteConfigurationRepository::class.java)).isNotNull
        assertThat(applicationContext.getBean(ExposedSyncStateRepository::class.java)).isNotNull
        assertThat(applicationContext.getBean(NotionApiClient::class.java)).isNotNull
        assertThat(applicationContext.getBean(NotionPostSource::class.java)).isNotNull
        assertThat(applicationContext.getBean(NotionSiteConfigurationSource::class.java)).isNotNull
        assertThat(applicationContext.getBean(PostSource::class.java)).isInstanceOf(NotionPostSource::class.java)
        assertThat(applicationContext.getBean(SiteConfigurationSource::class.java)).isInstanceOf(NotionSiteConfigurationSource::class.java)
        assertThat(
            applicationContext.getBean(PresentationAssetCatalog::class.java),
        ).isInstanceOf(ClasspathPresentationAssetCatalog::class.java)
        assertThat(applicationContext.getBean(SnapshotFailureReporter::class.java)).isInstanceOf(Slf4jSnapshotFailureReporter::class.java)
        assertThat(applicationContext.getBean(GetPublishedPostService::class.java)).isNotNull
        assertThat(applicationContext.getBean(ResolvePostLinksService::class.java)).isNotNull
        assertThat(applicationContext.getBean(GetBlogPageService::class.java)).isNotNull
        assertThat(applicationContext.getBean(PostPageViewAssembler::class.java)).isNotNull
        assertThat(applicationContext.getBean(BlogController::class.java)).isNotNull
        assertThat(applicationContext.getBean(SynchronizationQueryService::class.java)).isNotNull
        assertThat(applicationContext.getBean(SynchronizeSiteConfigurationService::class.java)).isNotNull
        assertThat(applicationContext.getBean(SynchronizePublicationService::class.java)).isNotNull
        assertThat(applicationContext.getBean(SynchronizePostService::class.java)).isNotNull
        assertThat(applicationContext.getBeansOfType(SynchronizationScheduler::class.java)).isEmpty()
        assertThat(applicationContext.containsBean("notionGateway")).isFalse()
        assertThat(applicationContext.containsBean("blogPersistence")).isFalse()
        assertThat(applicationContext.containsBean("pageRefreshExecutor")).isFalse()
        assertThat(applicationContext.containsBean("pageAccessService")).isFalse()
    }
}
