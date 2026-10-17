package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.application.port.input.CategoryManagementUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.HomeConfigurationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.PostCategoriesUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.service.CategoryManagementService
import xyz.robinjoon.notionblog.blog.application.service.HomeConfigurationService
import xyz.robinjoon.notionblog.blog.application.service.PostCategoriesService
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import java.util.UUID

@Configuration(proxyBeanMethods = false)
internal class HomeManagementConfig {
    @Bean
    fun homeConfigurationUseCase(configurations: HomeConfigurationRepository): HomeConfigurationUseCase =
        HomeConfigurationService(configurations)

    @Bean
    fun categoryManagementUseCase(categories: CategoryRepository): CategoryManagementUseCase =
        CategoryManagementService(categories) { CategoryId(UUID.randomUUID()) }

    @Bean
    fun postCategoriesUseCase(
        posts: PostRepository,
        categories: CategoryRepository,
    ): PostCategoriesUseCase = PostCategoriesService(posts, categories)
}
