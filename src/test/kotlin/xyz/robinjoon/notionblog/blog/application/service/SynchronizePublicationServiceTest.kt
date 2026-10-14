package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.ImportedPost
import xyz.robinjoon.notionblog.blog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.blog.application.model.PublicationSynchronizationContext
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionState
import java.util.UUID

class SynchronizePublicationServiceTest {
    private val publicationId = PublicationId(UUID.fromString("302bf506-fc10-4d30-8e5d-2bfd7c6a2e4e"))
    private val revisionId = PublicationRevisionId(UUID.fromString("21769b85-d04b-43b3-91e7-3e38b0523a67"))
    private val root = ref("root")

    @Test
    fun `source document mismatch fails synchronization`() {
        val service =
            service(
                posts = mapOf(root to imported(ref("different"))),
                appliedPostIds = mapOf(root to postId("1")),
            )

        assertThatIllegalArgumentException().isThrownBy { service.synchronize() }
    }

    @Test
    fun `duplicate source document in the structural graph fails synchronization`() {
        val child = ref("child")
        val service =
            service(
                posts = mapOf(root to imported(root, children = listOf(child, child))),
                appliedPostIds = mapOf(root to postId("1")),
            )

        assertThatIllegalArgumentException().isThrownBy { service.synchronize() }
    }

    @Test
    fun `structural cycle fails synchronization`() {
        val child = ref("child")
        val service =
            service(
                posts = mapOf(root to imported(root, children = listOf(child)), child to imported(child, children = listOf(root))),
                appliedPostIds = mapOf(root to postId("1"), child to postId("2")),
            )

        assertThatIllegalArgumentException().isThrownBy { service.synchronize() }
    }

    @Test
    fun `a document with multiple structural parents fails synchronization`() {
        val first = ref("first")
        val second = ref("second")
        val shared = ref("shared")
        val service =
            service(
                posts =
                    mapOf(
                        root to imported(root, children = listOf(first, second)),
                        first to imported(first, children = listOf(shared)),
                        second to imported(second, children = listOf(shared)),
                    ),
                appliedPostIds = mapOf(root to postId("1"), first to postId("2"), second to postId("3")),
            )

        assertThatIllegalArgumentException().isThrownBy { service.synchronize() }
    }

    @Test
    fun `source failures are rethrown`() {
        listOf(
            RetryableSourceException(),
            SourceAuthenticationException(),
            SourceAccessException(),
            SourceConfigurationException(),
            SourceMappingException(),
        ).forEach { failure ->
            val service = service(sourceFailure = failure)

            assertThatThrownBy { service.synchronize() }.isSameAs(failure)
        }
    }

    @Test
    fun `apply failure is propagated`() {
        val failure = RuntimeException("database unavailable")
        val service =
            service(
                posts = mapOf(root to imported(root)),
                appliedPostIds = mapOf(root to postId("1")),
                applyFailure = failure,
            )

        assertThatThrownBy { service.synchronize() }.isSameAs(failure)
    }

    private fun service(
        posts: Map<SourceDocumentRef, ImportedPost> = emptyMap(),
        appliedPostIds: Map<SourceDocumentRef, PostId> = emptyMap(),
        sourceFailure: SourceException? = null,
        applyFailure: RuntimeException? = null,
    ): SynchronizePublicationService {
        val source = mockk<PostSource>()
        every { source.fetch(any()) } answers {
            sourceFailure?.let { throw it }
            posts.getValue(firstArg())
        }
        val query = mockk<SynchronizationQueryService>()
        val stage = mockk<StagePublicationMemberService>()
        val apply = mockk<ApplyImportedPostService>()
        val activate = mockk<ActivatePublicationService>()
        every { query.loadPublication() } returns PublicationSynchronizationContext(publicationId, root)
        every { stage.begin(publicationId) } returns PublicationRevision(revisionId, publicationId, PublicationRevisionState.STAGING)
        every { stage.stage(any()) } just Runs
        every { stage.abandon(any(), any()) } just Runs
        every { apply.apply(any()) } answers {
            applyFailure?.let { throw it }
            appliedPostIds.getValue(firstArg<ImportedPost>().sourceDocument)
        }
        every { activate.activate(revisionId) } just Runs

        return SynchronizePublicationService(query, stage, source, apply, activate)
    }

    private fun imported(
        sourceDocument: SourceDocumentRef,
        children: List<SourceDocumentRef> = emptyList(),
    ) = ImportedPost(
        sourceDocument = sourceDocument,
        title = sourceDocument.externalId,
        publicationStatus = ImportedPublicationStatus.PUBLISHED,
        sourceRevision = SourceRevision("revision-${sourceDocument.externalId}"),
        content = BlockTree(emptyList()),
        containedChildren = children,
    )

    private fun ref(externalId: String) = SourceDocumentRef(SourceId("notion-main"), externalId)

    private fun postId(lastDigit: String) = PostId(UUID.fromString("00000000-0000-0000-0000-00000000000$lastDigit"))
}
