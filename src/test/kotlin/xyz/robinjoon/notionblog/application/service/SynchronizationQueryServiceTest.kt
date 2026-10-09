package xyz.robinjoon.notionblog.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.application.model.PublicationSynchronizationContext
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.memberFunctions

class SynchronizationQueryServiceTest {
    private val siteConfigurations = mockk<SiteConfigurationRepository>()
    private val publications = mockk<PublicationRepository>()
    private val posts = mockk<PostRepository>()
    private val syncStates = mockk<SyncStateRepository>()
    private val service = SynchronizationQueryService(siteConfigurations, publications, posts, syncStates)

    @Test
    fun `owns read only transactions for every public query without a component scanning stereotype`() {
        assertThat(SynchronizationQueryService::class.java.isAnnotationPresent(Service::class.java)).isFalse()

        val methods =
            SynchronizationQueryService::class
                .memberFunctions
                .filter { it.name in setOf("loadPublication", "loadPost", "findDueTargets") }

        assertThat(methods).hasSize(3)
        assertThat(methods).allSatisfy { method ->
            assertThat(
                method.annotations
                    .filterIsInstance<Transactional>()
                    .single()
                    .readOnly,
            ).isTrue()
        }
    }

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
        every { publications.findCurrent() } returns BlogPublication(PublicationId(UUID.randomUUID()), null, null)

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
            .isEqualTo(PublicationSynchronizationContext(publicationId, site.rootDocument))
    }

    @Test
    fun `loads a post only when it is an active member with a source binding`() {
        val publicationId = PublicationId(UUID.randomUUID())
        val postId = postId()
        val sourceDocument = sourceDocument("post")
        every { publications.findCurrent() } returns activePublication(publicationId)
        every { publications.findActiveMemberPostIds(publicationId, setOf(postId)) } returns emptySet()

        assertThat(service.loadPost(postId)).isNull()
        verify(exactly = 0) { posts.findBinding(postId) }

        every { publications.findActiveMemberPostIds(publicationId, setOf(postId)) } returns setOf(postId)
        every { posts.findBinding(postId) } returns null

        assertThat(service.loadPost(postId)).isNull()
    }

    @Test
    fun `resolves all active direct child bindings in one batch`() {
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
        verify(exactly = 1) { posts.findBindingsByPostIds(setOf(childOne, childTwo)) }
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
        verify(exactly = 0) { syncStates.findDue(any(), any()) }
    }

    private fun siteConfiguration(publicationId: PublicationId): SiteConfiguration =
        SiteConfiguration(
            publicationId = publicationId,
            rootDocument = sourceDocument("root"),
            headerDocument = null,
            footerDocument = null,
            metadata = SiteMetadata("Blog", null, "ko-KR", null),
            presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
        )

    private fun syncState(
        target: SyncTarget,
        refreshAfter: Instant,
    ): SyncState = SyncState(target, null, refreshAfter, 0, null)

    private fun activePublication(publicationId: PublicationId): BlogPublication =
        BlogPublication(publicationId, postId(), PublicationRevisionId(UUID.randomUUID()))

    private fun postId(): PostId = PostId(UUID.randomUUID())

    private fun sourceDocument(externalId: String): SourceDocumentRef = SourceDocumentRef(SourceId("notion-main"), externalId)
}
