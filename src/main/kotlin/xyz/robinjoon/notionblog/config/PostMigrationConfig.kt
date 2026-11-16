package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.application.port.input.PostMigrationUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostMigrationMappingRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostMigrationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
import xyz.robinjoon.notionblog.blog.application.service.ApplyMigratedPostService
import xyz.robinjoon.notionblog.blog.application.service.CapturePostMediaService
import xyz.robinjoon.notionblog.blog.application.service.MigrationPostQueryService
import xyz.robinjoon.notionblog.blog.application.service.PostMigrationService
import xyz.robinjoon.notionblog.blog.application.service.SyncStateRecorder
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class PostMigrationConfig(
    private val catalog: PostMigrationSource,
    private val revisions: PostRevisionSource,
    private val posts: PostRepository,
    private val mappings: PostMigrationMappingRepository,
    private val clock: Clock,
    private val failures: OperationFailureReporter,
) {
    @Bean
    fun migrationPostQueryService(): MigrationPostQueryService = MigrationPostQueryService(posts)

    @Bean
    fun applyMigratedPostService(
        media: MediaStoragePort,
        recorder: SyncStateRecorder,
    ): ApplyMigratedPostService = ApplyMigratedPostService(posts, mappings, media, recorder, clock)

    @Bean
    fun postMigrationUseCase(
        queries: MigrationPostQueryService,
        media: CapturePostMediaService,
        writer: ApplyMigratedPostService,
    ): PostMigrationUseCase = PostMigrationService(catalog, revisions, queries, media, writer, failures)
}
