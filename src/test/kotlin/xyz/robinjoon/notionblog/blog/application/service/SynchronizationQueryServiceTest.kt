package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.blog.application.model.PublicationSynchronizationContext
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.blog.domain.sync.SyncState
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Instant
import java.util.UUID

class SynchronizationQueryServiceTest {
    private val siteConfigurations = mockk<SiteConfigurationRepository>()
    private val publications = mockk<PublicationRepository>()
    private val posts = mockk<PostRepository>()
    private val syncStates = mockk<SyncStateRepository>()
    private val service = SynchronizationQueryService(siteConfigurations, publications, posts, syncStates)

    @Test
    fun `returns no publication context when current site configuration or publication is absent`() {
        every { siteConfigurations.findCurrent() } returns null
        assertThat(service.loadPublication()).isNull()

        val site = siteConfiguration(PublicationId(UUID.randomUUID()))
        every { siteConfigurations.findCurrent() } returns site
        every { publications.findCurrent() } returns null

        assertThat(service.loadPublication()).isNull()
    }

    @Test
    fun `rejects inconsistent site and publication ids`() {
        val sitePublicationId = PublicationId(UUID.randomUUID())
        val site = siteConfiguration(sitePublicationId)
        every { siteConfigurations.findCurrent() } returns site
        every { publications.findCurrent() } returns BlogPublication.restore(PublicationId(UUID.randomUUID()), null, null)

        assertThatThrownBy { service.loadPublication() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `loads root source reference even before an active revision exists`() {
        val publicationId = PublicationId(UUID.randomUUID())
        val site = siteConfiguration(publicationId)
        every { siteConfigurations.findCurrent() } returns site
        every { publications.findCurrent() } returns activePublication(publicationId)

        assertThat(service.loadPublication())
            .isEqualTo(PublicationSynchronizationContext(publicationId, site.documents.root))
    }

    @Test
    fun `loads a post only when it is an active member with a source binding`() {
        val publicationId = PublicationId(UUID.randomUUID())
        val postId = postId()
        val sourceDocument = sourceDocument("post")
        every { publications.findCurrent() } returns activePublication(publicationId)
        every { publications.findActiveMemberPostIds(publicationId, setOf(postId)) } returns emptySet()

        assertThat(service.loadPost(postId)).isNull()

        every { publications.findActiveMemberPostIds(publicationId, setOf(postId)) } returns setOf(postId)
        every { posts.findBinding(postId) } returns null

        assertThat(service.loadPost(postId)).isNull()
    }

    @Test
    fun `resolves all active direct child bindings`() {
        val publicationId = PublicationId(UUID.randomUUID())
        val postId = postId()
        val childOne = postId()
        val childTwo = postId()
        val sourceDocument = sourceDocument("post")
        val childOneSource = sourceDocument("child-one")
        val childTwoSource = sourceDocument("child-two")
        every { publications.findCurrent() } returns activePublication(publicationId)
        every { publications.findActiveMemberPostIds(publicationId, setOf(postId)) } returns setOf(postId)
        every { posts.findBinding(postId) } returns PostSourceBinding(postId, sourceDocument)
        every { publications.findActiveDirectChildren(publicationId, postId) } returns
            listOf(
                PublicationMember(PublicationRevisionId(UUID.randomUUID()), childOne, postId, 1),
                PublicationMember(PublicationRevisionId(UUID.randomUUID()), childTwo, postId, 1),
            )
        every { posts.findBindingsByPostIds(setOf(childOne, childTwo)) } returns
            mapOf(
                childOne to PostSourceBinding(childOne, childOneSource),
                childTwo to PostSourceBinding(childTwo, childTwoSource),
            )

        assertThat(service.loadPost(postId)).isEqualTo(
            PostSynchronizationContext(
                publicationId = publicationId,
                postId = postId,
                sourceDocument = sourceDocument,
                activeDirectChildren = setOf(childOneSource, childTwoSource),
            ),
        )
    }

    @Test
    fun `fails when an active direct child has no source binding`() {
        val publicationId = PublicationId(UUID.randomUUID())
        val postId = postId()
        val child = postId()
        every { publications.findCurrent() } returns activePublication(publicationId)
        every { publications.findActiveMemberPostIds(publicationId, setOf(postId)) } returns setOf(postId)
        every { posts.findBinding(postId) } returns PostSourceBinding(postId, sourceDocument("post"))
        every { publications.findActiveDirectChildren(publicationId, postId) } returns
            listOf(
                PublicationMember(PublicationRevisionId(UUID.randomUUID()), child, postId, 1),
            )
        every { posts.findBindingsByPostIds(setOf(child)) } returns emptyMap()

        assertThatThrownBy { service.loadPost(postId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `returns due targets in repository order`() {
        val now = Instant.parse("2026-08-25T00:00:00Z")
        val first = SyncTarget.Post(postId())
        val second = SyncTarget.SiteConfiguration
        val states = listOf(syncState(first, now), syncState(second, now))
        every { syncStates.findDue(now, 2) } returns states

        assertThat(service.findDueTargets(now, 2)).containsExactly(first, second)
    }

    @Test
    fun `rejects non positive due target limits`() {
        assertThatThrownBy { service.findDueTargets(Instant.EPOCH, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.findDueTargets(Instant.EPOCH, -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun siteConfiguration(publicationId: PublicationId): SiteConfiguration =
        SiteConfiguration.restore(
            publicationId = publicationId,
            documents = SiteDocuments(root = sourceDocument("root"), header = null, footer = null),
            metadata = SiteMetadata("Blog", null, "ko-KR", null),
            presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
        )

    private fun syncState(
        target: SyncTarget,
        refreshAfter: Instant,
    ): SyncState = SyncState.restore(target, null, refreshAfter, null)

    private fun activePublication(publicationId: PublicationId): BlogPublication =
        BlogPublication.restore(publicationId, postId(), PublicationRevisionId(UUID.randomUUID()))

    private fun postId(): PostId = PostId(UUID.randomUUID())

    private fun sourceDocument(externalId: String): SourceDocumentRef = SourceDocumentRef(SourceId("notion-main"), externalId)
}
