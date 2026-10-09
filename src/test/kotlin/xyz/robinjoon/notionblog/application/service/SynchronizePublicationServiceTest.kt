package xyz.robinjoon.notionblog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.model.PublicationSynchronizationContext
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import java.util.UUID

class SynchronizePublicationServiceTest {
    private val publicationId = PublicationId(UUID.fromString("302bf506-fc10-4d30-8e5d-2bfd7c6a2e4e"))
    private val revisionId = PublicationRevisionId(UUID.fromString("21769b85-d04b-43b3-91e7-3e38b0523a67"))
    private val root = ref("root")

    @Test
    fun `synchronize is not transactional and does nothing when no publication is configured`() {
        val fixture = fixture()
        every { fixture.query.loadPublication() } returns null

        fixture.service.synchronize()

        assertThat(SynchronizePublicationService::class.java.isAnnotationPresent(Transactional::class.java)).isFalse()
        assertThat(
            SynchronizePublicationService::class.java
                .getDeclaredMethod("synchronize")
                .isAnnotationPresent(Transactional::class.java),
        ).isFalse()
        assertThat(fixture.events).isEmpty()
    }

    @Test
    fun `root only publication fetches applies stages then activates root`() {
        val rootPost = postId("1")
        val fixture = fixture(posts = mapOf(root to imported(root)), appliedPostIds = mapOf(root to rootPost))

        fixture.service.synchronize()

        assertThat(fixture.members).containsExactly(PublicationMember(revisionId, rootPost, null, 0))
        assertThat(fixture.events).containsExactly("begin", "fetch:root", "apply:root", "stage:root", "activate")
    }

    @Test
    fun `structural children are visited breadth first with their parent and depth`() {
        val first = ref("first")
        val second = ref("second")
        val grandchild = ref("grandchild")
        val fixture =
            fixture(
                posts =
                    mapOf(
                        root to imported(root, children = listOf(first, second)),
                        first to imported(first, children = listOf(grandchild)),
                        second to imported(second),
                        grandchild to imported(grandchild),
                    ),
                appliedPostIds =
                    mapOf(
                        root to postId("1"),
                        first to postId("2"),
                        second to postId("3"),
                        grandchild to postId("4"),
                    ),
            )

        fixture.service.synchronize()

        assertThat(fixture.members).containsExactly(
            PublicationMember(revisionId, postId("1"), null, 0),
            PublicationMember(revisionId, postId("2"), postId("1"), 1),
            PublicationMember(revisionId, postId("3"), postId("1"), 1),
            PublicationMember(revisionId, postId("4"), postId("2"), 2),
        )
        assertThat(fixture.events).containsSubsequence(
            "fetch:root",
            "fetch:first",
            "fetch:second",
            "fetch:grandchild",
            "activate",
        )
    }

    @Test
    fun `unpublished parent remains a member and does not stop descendant collection`() {
        val parent = ref("parent")
        val descendant = ref("descendant")
        val fixture =
            fixture(
                posts =
                    mapOf(
                        root to imported(root, children = listOf(parent)),
                        parent to imported(parent, ImportedPublicationStatus.UNPUBLISHED, listOf(descendant)),
                        descendant to imported(descendant),
                    ),
                appliedPostIds = mapOf(root to postId("1"), parent to postId("2"), descendant to postId("3")),
            )

        fixture.service.synchronize()

        assertThat(fixture.members).containsExactly(
            PublicationMember(revisionId, postId("1"), null, 0),
            PublicationMember(revisionId, postId("2"), postId("1"), 1),
            PublicationMember(revisionId, postId("3"), postId("2"), 2),
        )
        assertThat(fixture.events).contains("fetch:descendant", "activate")
    }

    @Test
    fun `ordinary content links are ignored because only contained children are traversed`() {
        val ordinaryLink = ref("ordinary-link")
        val rootWithLink =
            imported(
                root,
                content =
                    BlockTree(
                        listOf(
                            BlockNode(
                                BlockId("ordinary-link"),
                                ReferenceBlockContent.DocumentLink(ordinaryLink, originalUrl = null),
                            ),
                        ),
                    ),
            )
        val fixture = fixture(posts = mapOf(root to rootWithLink), appliedPostIds = mapOf(root to postId("1")))

        fixture.service.synchronize()

        assertThat(fixture.source.requests).doesNotContain(ordinaryLink)
        assertThat(fixture.members).hasSize(1)
    }

    @Test
    fun `source document mismatch abandons the staging revision exactly once as a mapping failure`() {
        val fixture =
            fixture(
                posts = mapOf(root to imported(ref("different"))),
                appliedPostIds = mapOf(root to postId("1")),
            )

        assertThatIllegalArgumentException().isThrownBy { fixture.service.synchronize() }

        assertThat(fixture.abandoned).containsExactly(revisionId to SyncFailureKind.MAPPING)
        assertThat(fixture.events).containsExactly("begin", "fetch:root", "abandon:MAPPING")
    }

    @Test
    fun `duplicate source document in the structural graph abandons staging as a mapping failure`() {
        val child = ref("child")
        val fixture =
            fixture(
                posts = mapOf(root to imported(root, children = listOf(child, child))),
                appliedPostIds = mapOf(root to postId("1")),
            )

        assertThatIllegalArgumentException().isThrownBy { fixture.service.synchronize() }

        assertThat(fixture.abandoned).containsExactly(revisionId to SyncFailureKind.MAPPING)
        assertThat(fixture.events).containsExactly("begin", "fetch:root", "apply:root", "stage:root", "abandon:MAPPING")
    }

    @Test
    fun `structural cycle abandons staging as a mapping failure`() {
        val child = ref("child")
        val fixture =
            fixture(
                posts = mapOf(root to imported(root, children = listOf(child)), child to imported(child, children = listOf(root))),
                appliedPostIds = mapOf(root to postId("1"), child to postId("2")),
            )

        assertThatIllegalArgumentException().isThrownBy { fixture.service.synchronize() }

        assertThat(fixture.abandoned).containsExactly(revisionId to SyncFailureKind.MAPPING)
        assertThat(fixture.events).doesNotContain("activate")
    }

    @Test
    fun `a document with multiple structural parents abandons staging as a mapping failure`() {
        val first = ref("first")
        val second = ref("second")
        val shared = ref("shared")
        val fixture =
            fixture(
                posts =
                    mapOf(
                        root to imported(root, children = listOf(first, second)),
                        first to imported(first, children = listOf(shared)),
                        second to imported(second, children = listOf(shared)),
                    ),
                appliedPostIds = mapOf(root to postId("1"), first to postId("2"), second to postId("3")),
            )

        assertThatIllegalArgumentException().isThrownBy { fixture.service.synchronize() }

        assertThat(fixture.abandoned).containsExactly(revisionId to SyncFailureKind.MAPPING)
        assertThat(fixture.events).doesNotContain("activate")
    }

    @Test
    fun `source failures are classified abandoned and rethrown`() {
        listOf(
            RetryableSourceException() to SyncFailureKind.RETRYABLE_SOURCE,
            SourceAuthenticationException() to SyncFailureKind.AUTHENTICATION,
            SourceAccessException() to SyncFailureKind.ACCESS,
            SourceConfigurationException() to SyncFailureKind.CONFIGURATION,
            SourceMappingException() to SyncFailureKind.MAPPING,
        ).forEach { (failure, kind) ->
            val fixture = fixture(sourceFailure = failure)

            assertThatThrownBy { fixture.service.synchronize() }.isSameAs(failure)

            assertThat(fixture.abandoned).containsExactly(revisionId to kind)
            assertThat(fixture.events).containsExactly("begin", "fetch:root", "abandon:$kind")
        }
    }

    @Test
    fun `apply failure is propagated without falsely abandoning the staging revision`() {
        val failure = RuntimeException("database unavailable")
        val fixture =
            fixture(
                posts = mapOf(root to imported(root)),
                appliedPostIds = mapOf(root to postId("1")),
                applyFailure = failure,
            )

        assertThatThrownBy { fixture.service.synchronize() }.isSameAs(failure)

        assertThat(fixture.abandoned).isEmpty()
        assertThat(fixture.events).containsExactly("begin", "fetch:root", "apply:root")
    }

    @Test
    fun `source fetch observes no active transaction and activation is the final operation`() {
        val fixture = fixture(posts = mapOf(root to imported(root)), appliedPostIds = mapOf(root to postId("1")))

        fixture.service.synchronize()

        assertThat(fixture.source.transactionActiveAtFetch).containsExactly(false)
        assertThat(fixture.events.last()).isEqualTo("activate")
    }

    private fun fixture(
        posts: Map<SourceDocumentRef, ImportedPost> = emptyMap(),
        appliedPostIds: Map<SourceDocumentRef, PostId> = emptyMap(),
        sourceFailure: SourceException? = null,
        applyFailure: RuntimeException? = null,
    ): Fixture {
        val events = mutableListOf<String>()
        val members = mutableListOf<PublicationMember>()
        val abandoned = mutableListOf<Pair<PublicationRevisionId, SyncFailureKind>>()
        val source = RecordingPostSource(posts, sourceFailure, events)
        val sourceLabelByPostId =
            appliedPostIds.entries.associate { (sourceDocument, postId) ->
                postId to sourceDocument.externalId
            }
        val query = mockk<SynchronizationQueryService>()
        val stage = mockk<StagePublicationMemberService>()
        val apply = mockk<ApplyImportedPostService>()
        val activate = mockk<ActivatePublicationService>()
        every { query.loadPublication() } returns PublicationSynchronizationContext(publicationId, root)
        every { stage.begin(publicationId) } answers {
            events += "begin"
            PublicationRevision(revisionId, publicationId, PublicationRevisionState.STAGING)
        }
        every { stage.stage(any()) } answers {
            val member = firstArg<PublicationMember>()
            members += member
            events += "stage:${sourceLabelByPostId.getValue(member.postId)}"
        }
        every { stage.abandon(any(), any()) } answers {
            val kind = secondArg<SyncFailureKind>()
            abandoned += revisionId to kind
            events += "abandon:$kind"
        }
        every { apply.apply(any()) } answers {
            val imported = firstArg<ImportedPost>()
            events += "apply:${imported.sourceDocument.externalId}"
            applyFailure?.let { throw it }
            appliedPostIds.getValue(imported.sourceDocument)
        }
        every { activate.activate(revisionId) } answers { events += "activate" }

        return Fixture(
            service = SynchronizePublicationService(query, stage, source, apply, activate),
            query = query,
            source = source,
            events = events,
            members = members,
            abandoned = abandoned,
        )
    }

    private fun imported(
        sourceDocument: SourceDocumentRef,
        status: ImportedPublicationStatus = ImportedPublicationStatus.PUBLISHED,
        children: List<SourceDocumentRef> = emptyList(),
        content: BlockTree = BlockTree(emptyList()),
    ) = ImportedPost(
        sourceDocument = sourceDocument,
        title = sourceDocument.externalId,
        publicationStatus = status,
        sourceRevision = SourceRevision("revision-${sourceDocument.externalId}"),
        content = content,
        containedChildren = children,
    )

    private fun ref(externalId: String) = SourceDocumentRef(SourceId("notion-main"), externalId)

    private fun postId(lastDigit: String) = PostId(UUID.fromString("00000000-0000-0000-0000-00000000000$lastDigit"))

    private data class Fixture(
        val service: SynchronizePublicationService,
        val query: SynchronizationQueryService,
        val source: RecordingPostSource,
        val events: List<String>,
        val members: List<PublicationMember>,
        val abandoned: List<Pair<PublicationRevisionId, SyncFailureKind>>,
    )

    private class RecordingPostSource(
        private val posts: Map<SourceDocumentRef, ImportedPost>,
        private val failure: SourceException?,
        private val events: MutableList<String>,
    ) : PostSource {
        val requests = mutableListOf<SourceDocumentRef>()
        val transactionActiveAtFetch = mutableListOf<Boolean>()

        override fun fetch(reference: SourceDocumentRef): ImportedPost {
            requests += reference
            transactionActiveAtFetch += TransactionSynchronizationManager.isActualTransactionActive()
            events += "fetch:${reference.externalId}"
            failure?.let { throw it }
            return posts.getValue(reference)
        }
    }
}
