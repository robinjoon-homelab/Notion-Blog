package xyz.robinjoon.notionblog.application.port.input

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.application.service.GetBlogPageService
import xyz.robinjoon.notionblog.application.service.GetPostFeedService
import xyz.robinjoon.notionblog.application.service.SynchronizationQueryService
import xyz.robinjoon.notionblog.application.service.SynchronizePostService
import xyz.robinjoon.notionblog.application.service.SynchronizePublicationService
import xyz.robinjoon.notionblog.application.service.SynchronizeSiteConfigurationService

class InputPortBoundaryContractTest {
    @Test
    fun `external entry points implement explicit use case contracts`() {
        val implementations =
            mapOf(
                "GetBlogPageUseCase" to GetBlogPageService::class.java,
                "GetPostFeedUseCase" to GetPostFeedService::class.java,
                "SynchronizationQueryUseCase" to SynchronizationQueryService::class.java,
                "SynchronizePostUseCase" to SynchronizePostService::class.java,
                "SynchronizePublicationUseCase" to SynchronizePublicationService::class.java,
                "SynchronizeSiteConfigurationUseCase" to SynchronizeSiteConfigurationService::class.java,
            )

        implementations.forEach { (name, implementation) ->
            val contract = contract(name)
            assertThat(contract.isInterface).describedAs(name).isTrue()
            assertThat(contract.isAssignableFrom(implementation)).describedAs(name).isTrue()
        }
    }

    @Test
    fun `input contracts expose only operations required by their inbound adapters`() {
        val operations =
            mapOf(
                "GetBlogPageUseCase" to setOf("getRoot", "get"),
                "GetPostFeedUseCase" to setOf("get"),
                "SynchronizationQueryUseCase" to setOf("findDueTargets"),
                "SynchronizePostUseCase" to setOf("synchronize"),
                "SynchronizePublicationUseCase" to setOf("synchronize"),
                "SynchronizeSiteConfigurationUseCase" to setOf("synchronize"),
            )

        operations.forEach { (name, expected) ->
            val methods = contract(name).declaredMethods
            assertThat(methods.map { it.name.substringBefore('-') })
                .describedAs(name)
                .containsExactlyInAnyOrderElementsOf(expected)
            assertThat(methods.none { it.isDefault }).describedAs(name).isTrue()
        }
    }

    private fun contract(name: String): Class<*> = Class.forName("xyz.robinjoon.notionblog.application.port.input.$name")
}
