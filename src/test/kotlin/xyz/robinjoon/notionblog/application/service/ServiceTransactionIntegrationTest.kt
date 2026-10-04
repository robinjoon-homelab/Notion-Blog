package xyz.robinjoon.notionblog.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import xyz.robinjoon.notionblog.adapter.output.persistence.exposed.ExposedSyncStateRepository
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.model.StoredPost
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(
    properties = [
        "notion.token=test-token",
        "notion.settings-data-source-id=settings-data-source",
        "notion.base-url=http://127.0.0.1:1/v1",
        "blog.synchronization.enabled=false",
    ],
)
@Import(ServiceTransactionIntegrationTest.TransactionTestConfiguration::class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceTransactionIntegrationTest(
    @Autowired private val applyService: ApplyImportedPostService,
    @Autowired private val activateService: ActivatePublicationService,
    @Autowired private val synchronizePostService: SynchronizePostService,
    @Autowired private val posts: PostRepository,
    @Autowired private val publications: PublicationRepository,
    @Autowired private val states: FailingSyncStateRepository,
    @Autowired private val source: RecordingPostSource,
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired dataSource: DataSource,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun clearDatabaseAndFailures() {
        states.failAfterSaveFor = null
        source.imports.clear()
        source.transactionStates.clear()
        jdbc.execute(
            "truncate table site_configuration, publication_member, publication_revision, publication, " +
                "post_availability, post_snapshot, post_source_binding, post, sync_state cascade",
        )
    }

    @Test
    fun `activation failure rolls back both revision states the active pointer and synchronization state`() {
        val originalRootId = applyService.apply(imported("original-root", "Original root", "revision-1"))
        val replacementRootId = applyService.apply(imported("replacement-root", "Replacement root", "revision-1"))
        val originalPublication = seedActivePublication(originalRootId)
        val replacementRevision = stagingRevision(originalPublication.id)
        val previousState = failedState(SyncTarget.Publication(originalPublication.id))
        val previousPostState = failedState(SyncTarget.Post(originalRootId))
        inTransaction {
            publications.createRevision(replacementRevision, NOW)
            publications.saveMembers(
                replacementRevision.id,
                listOf(PublicationMember(replacementRevision.id, replacementRootId, parentPostId = null, depth = 0)),
            )
            states.save(previousState)
            states.save(previousPostState)
        }
        states.failAfterSaveFor = previousState.target

        assertThatThrownBy { activateService.activate(replacementRevision.id) }
            .isInstanceOf(InjectedWriteFailure::class.java)

        inTransaction {
            assertThat(publications.findCurrent()).isEqualTo(originalPublication)
            assertThat(publications.findActiveRevision(originalPublication.id)?.id).isEqualTo(originalPublication.activeRevisionId)
            assertThat(publications.findRevision(requireNotNull(originalPublication.activeRevisionId))?.state)
                .isEqualTo(PublicationRevisionState.ACTIVE)
            assertThat(publications.findRevision(replacementRevision.id)).isEqualTo(replacementRevision)
            assertThat(states.find(previousState.target)).isEqualTo(previousState)
            assertThat(states.find(previousPostState.target)).isEqualTo(previousPostState)
        }
    }

    @Test
    fun `activation removes excluded post reservations while preserving unpublished and moved members`() {
        val rootId = applyService.apply(imported("root", "Root", "revision-1"))
        val removedId = applyService.apply(imported("removed", "Removed", "revision-1"))
        val unpublishedId = applyService.apply(
            imported("unpublished", "Unpublished", "revision-1").copy(publicationStatus = ImportedPublicationStatus.UNPUBLISHED),
        )
        val movedId = applyService.apply(imported("moved", "Moved", "revision-1"))
        val publication = seedActivePublication(rootId)
        val originalRevisionId = requireNotNull(publication.activeRevisionId)
        val replacement = stagingRevision(publication.id)
        val retainedStates = listOf(rootId, unpublishedId, movedId).map { failedState(SyncTarget.Post(it)) }
        val excludedState = SyncState(SyncTarget.Post(removedId), NOW, NOW.plusSeconds(3_600), 0, null)
        val settingsState = failedState(SyncTarget.SiteConfiguration)
        inTransaction {
            publications.saveMembers(
                originalRevisionId,
                listOf(removedId, unpublishedId, movedId).map { PublicationMember(originalRevisionId, it, rootId, 1) },
            )
            publications.createRevision(replacement, NOW)
            publications.saveMembers(
                replacement.id,
                listOf(
                    PublicationMember(replacement.id, rootId, null, 0),
                    PublicationMember(replacement.id, unpublishedId, rootId, 1),
                    PublicationMember(replacement.id, movedId, unpublishedId, 2),
                ),
            )
            (retainedStates + excludedState + settingsState).forEach(states::save)
        }

        activateService.activate(replacement.id)

        inTransaction {
            assertThat(publications.findCurrent()?.activeRevisionId).isEqualTo(replacement.id)
            assertThat(states.find(excludedState.target)).isNull()
            (retainedStates + settingsState).forEach { assertThat(states.find(it.target)).isEqualTo(it) }
            assertThat(posts.find(removedId)?.post?.title).isEqualTo("Removed")
            assertThat(posts.findBinding(removedId)).isNotNull()
            assertThat(posts.findAvailability(removedId)?.status).isEqualTo(PostAvailabilityStatus.PUBLISHED)
        }
    }

    @Test
    fun `skipping an old excluded reservation frees the next due slot without fetching or deleting content`() {
        val rootId = applyService.apply(imported("root", "Root", "revision-1"))
        val excludedId = applyService.apply(imported("excluded", "Excluded", "revision-1"))
        val publication = seedActivePublication(rootId)
        val activeState = SyncState(SyncTarget.Post(rootId), NOW, NOW, 0, null)
        val excludedState = failedState(SyncTarget.Post(excludedId))
        val publicationState = SyncState(SyncTarget.Publication(publication.id), NOW, NOW.plusSeconds(600), 0, null)
        inTransaction {
            listOf(activeState, excludedState, publicationState).forEach(states::save)
            assertThat(states.findDue(NOW, 1)).containsExactly(excludedState)
        }

        synchronizePostService.synchronize(excludedId)

        assertThat(source.transactionStates).isEmpty()
        inTransaction {
            assertThat(states.find(excludedState.target)).isNull()
            assertThat(states.findDue(NOW, 1)).containsExactly(activeState)
            assertThat(states.find(publicationState.target)).isEqualTo(publicationState)
            assertThat(posts.find(excludedId)?.post?.title).isEqualTo("Excluded")
        }

        val returnedId = applyService.apply(imported("excluded", "Returned", "revision-2"))
        assertThat(returnedId).isEqualTo(excludedId)
        inTransaction {
            assertThat(states.find(excludedState.target)?.lastSuccessAt).isEqualTo(NOW)
            assertThat(states.find(excludedState.target)?.refreshAfter).isAfter(NOW)
        }
    }

    @Test
    fun `missing initialization or an active source binding does not cancel a post reservation`() {
        val postId = applyService.apply(imported("post", "Post", "revision-1"))
        val reservation = failedState(SyncTarget.Post(postId))
        inTransaction { states.save(reservation) }

        synchronizePostService.synchronize(postId)
        inTransaction { assertThat(states.find(reservation.target)).isEqualTo(reservation) }

        val publicationId = PublicationId(UUID.randomUUID())
        inTransaction { publications.save(BlogPublication(publicationId, null, null)) }
        synchronizePostService.synchronize(postId)
        inTransaction { assertThat(states.find(reservation.target)).isEqualTo(reservation) }

        val revision = stagingRevision(publicationId)
        inTransaction {
            publications.createRevision(revision, NOW)
            publications.saveMembers(revision.id, listOf(PublicationMember(revision.id, postId, null, 0)))
            publications.updateRevision(revision.activate(), NOW)
            publications.save(BlogPublication(publicationId, postId, revision.id))
        }
        jdbc.update("delete from post_source_binding where post_id = ?", postId.value)

        synchronizePostService.synchronize(postId)

        assertThat(source.transactionStates).isEmpty()
        inTransaction { assertThat(states.find(reservation.target)).isEqualTo(reservation) }
    }

    @Test
    fun `post write failure rolls back title snapshot publication status and synchronization state`() {
        val original = imported("post", "Original title", "revision-1")
        val postId = applyService.apply(original)
        val originalSnapshot = StoredPost(Post(postId, original.title, original.content), original.sourceRevision, NOW)
        val originalAvailability = PostAvailability(postId, PostAvailabilityStatus.UNPUBLISHED, BEFORE)
        val previousState = failedState(SyncTarget.Post(postId))
        inTransaction {
            posts.saveAvailability(originalAvailability)
            states.save(previousState)
        }
        states.failAfterSaveFor = previousState.target
        val replacement = imported("post", "Replacement title", "revision-2")

        assertThatThrownBy { applyService.apply(replacement) }
            .isInstanceOf(InjectedWriteFailure::class.java)

        inTransaction {
            assertThat(posts.find(postId)).isEqualTo(originalSnapshot)
            assertThat(posts.findAvailability(postId)).isEqualTo(originalAvailability)
            assertThat(states.find(previousState.target)).isEqualTo(previousState)
        }
    }

    @Test
    fun `post synchronization fetches outside Spring and Exposed transactions and commits the imported content`() {
        val original = imported("post", "Original title", "revision-1")
        val postId = applyService.apply(original)
        seedActivePublication(postId)
        val replacement = imported("post", "Replacement title", "revision-2")
        source.imports[replacement.sourceDocument] = replacement

        synchronizePostService.synchronize(postId)

        assertThat(source.transactionStates).containsExactly(false to false)
        inTransaction {
            assertThat(posts.find(postId))
                .isEqualTo(StoredPost(Post(postId, replacement.title, replacement.content), replacement.sourceRevision, NOW))
            assertThat(posts.findAvailability(postId))
                .isEqualTo(PostAvailability(postId, PostAvailabilityStatus.PUBLISHED, NOW))
            assertThat(states.find(SyncTarget.Post(postId))?.lastSuccessAt).isEqualTo(NOW)
        }
    }

    private fun seedActivePublication(rootPostId: PostId): BlogPublication {
        val publicationId = PublicationId(UUID.randomUUID())
        val revision = stagingRevision(publicationId)
        val publication = BlogPublication(publicationId, rootPostId, revision.id)
        inTransaction {
            publications.save(BlogPublication(publicationId, rootPostId = null, activeRevisionId = null))
            publications.createRevision(revision, BEFORE)
            publications.saveMembers(
                revision.id,
                listOf(PublicationMember(revision.id, rootPostId, parentPostId = null, depth = 0)),
            )
            publications.updateRevision(revision.activate(), BEFORE)
            publications.save(publication)
        }
        return publication
    }

    private fun stagingRevision(publicationId: PublicationId) = PublicationRevision(
        PublicationRevisionId(UUID.randomUUID()),
        publicationId,
        PublicationRevisionState.STAGING,
    )

    private fun failedState(target: SyncTarget) = SyncState(target, BEFORE, BEFORE, 2, SyncFailureKind.MAPPING)

    private fun imported(externalId: String, title: String, revision: String) = ImportedPost(
        sourceDocument = SourceDocumentRef(SourceId("test-source"), externalId),
        title = title,
        publicationStatus = ImportedPublicationStatus.PUBLISHED,
        sourceRevision = SourceRevision(revision),
        content = BlockTree(listOf(BlockNode(BlockId("paragraph"), TextBlockContent.Paragraph(listOf(InlineContent.Text(title)))))),
        containedChildren = emptyList(),
    )

    private fun inTransaction(block: () -> Unit) = transactions.executeWithoutResult { block() }

    @TestConfiguration(proxyBeanMethods = false)
    class TransactionTestConfiguration {
        @Bean
        @Primary
        fun integrationClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean
        @Primary
        fun failingSyncStateRepository(delegate: ExposedSyncStateRepository): FailingSyncStateRepository = FailingSyncStateRepository(delegate)

        @Bean
        @Primary
        fun recordingPostSource(): RecordingPostSource = RecordingPostSource()
    }

    class FailingSyncStateRepository(private val delegate: SyncStateRepository) : SyncStateRepository by delegate {
        var failAfterSaveFor: SyncTarget? = null

        override fun save(state: SyncState) {
            delegate.save(state)
            if (state.target == failAfterSaveFor) {
                throw InjectedWriteFailure()
            }
        }
    }

    class RecordingPostSource : PostSource {
        val imports = mutableMapOf<SourceDocumentRef, ImportedPost>()
        val transactionStates = mutableListOf<Pair<Boolean, Boolean>>()

        override fun fetch(reference: SourceDocumentRef): ImportedPost {
            transactionStates += TransactionSynchronizationManager.isActualTransactionActive() to (TransactionManager.currentOrNull() != null)
            return imports.getValue(reference)
        }
    }

    class InjectedWriteFailure : RuntimeException("injected failure after writing synchronization state")

    companion object {
        private val NOW = Instant.parse("2026-08-25T01:00:00Z")
        private val BEFORE = NOW.minusSeconds(60)

        @Container
        @JvmStatic
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", container::getJdbcUrl)
            registry.add("spring.datasource.username", container::getUsername)
            registry.add("spring.datasource.password", container::getPassword)
        }
    }
}
