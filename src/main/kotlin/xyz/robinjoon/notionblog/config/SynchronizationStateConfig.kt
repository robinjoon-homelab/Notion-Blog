package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.application.port.input.MigrateLegacyPostsUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.PostPublicationUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.LegacyPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.application.service.ApplyImportedPostService
import xyz.robinjoon.notionblog.blog.application.service.CancelSynchronizationService
import xyz.robinjoon.notionblog.blog.application.service.MigrateLegacyPostsService
import xyz.robinjoon.notionblog.blog.application.service.PublishPostService
import xyz.robinjoon.notionblog.blog.application.service.RegisterPostService
import xyz.robinjoon.notionblog.blog.application.service.SyncStateRecorder
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import java.time.Clock

// 짧은 쓰기 트랜잭션이다. 각 서비스가 자신의 트랜잭션을 소유하며, 어느 서비스도 원본을 호출하거나 파일을 내려받지 않는다.
@Configuration(proxyBeanMethods = false)
internal class SynchronizationStateConfig(
    private val posts: PostRepository,
    private val clock: Clock,
    private val postIdFactory: () -> PostId,
    private val revisionIdFactory: () -> PostRevisionId,
) {
    @Bean
    fun syncStateRecorder(
        states: SyncStateRepository,
        refreshPolicy: RefreshPolicy,
    ): SyncStateRecorder = SyncStateRecorder(states, refreshPolicy)

    @Bean
    fun applyImportedPostService(
        mediaStorage: MediaStoragePort,
        recorder: SyncStateRecorder,
    ): ApplyImportedPostService = ApplyImportedPostService(posts, mediaStorage, recorder, clock, revisionIdFactory)

    @Bean
    fun registerPostService(recorder: SyncStateRecorder): RegisterPostService = RegisterPostService(posts, recorder, clock, postIdFactory)

    @Bean
    fun postPublicationUseCase(recorder: SyncStateRecorder): PostPublicationUseCase = PublishPostService(posts, recorder, clock)

    @Bean
    fun cancelSynchronizationService(states: SyncStateRepository): CancelSynchronizationService =
        CancelSynchronizationService(posts, states)

    @Bean
    fun migrateLegacyPostsUseCase(
        legacy: LegacyPostStore,
        recorder: SyncStateRecorder,
    ): MigrateLegacyPostsUseCase = MigrateLegacyPostsService(legacy, posts, recorder, clock, revisionIdFactory)
}
