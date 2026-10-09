package xyz.robinjoon.notionblog.domain.publication

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.post.PostId
import java.time.Instant
import java.util.UUID

class PublicationPolicyTest {
    private val publicationId = PublicationId(UUID.fromString("d2719cb7-bf66-4af3-a007-7fd249c1b414"))
    private val revisionId = PublicationRevisionId(UUID.fromString("e14a60a4-b2d3-40cc-94c4-c586e8bcc57e"))
    private val rootPostId = PostId(UUID.fromString("96bfdb45-7f5a-46fc-bf7e-e3d5495819b8"))
    private val unpublishedParentPostId = PostId(UUID.fromString("a6f4fd8b-05c5-4e67-a6ce-e2a05d885b93"))
    private val publishedDescendantPostId = PostId(UUID.fromString("bd7446f5-9e40-4a3f-8118-fa4d1197d165"))

    @Test
    fun `an unpublished parent remains a structural member when its published descendant is activated`() {
        val revision = stagingRevision()
        val members =
            listOf(
                PublicationMember(revision.id, rootPostId, parentPostId = null, depth = 0),
                PublicationMember(revision.id, unpublishedParentPostId, parentPostId = rootPostId, depth = 1),
                PublicationMember(
                    revision.id,
                    publishedDescendantPostId,
                    parentPostId = unpublishedParentPostId,
                    depth = 2,
                ),
            )
        val availabilityByPostId =
            mapOf(
                rootPostId to published(rootPostId),
                unpublishedParentPostId to unpublished(unpublishedParentPostId),
                publishedDescendantPostId to published(publishedDescendantPostId),
            )

        assertThatCode {
            PublicationPolicy.validateForActivation(
                revision = revision,
                members = members,
                availabilityByPostId = availabilityByPostId,
                renderablePostIds = setOf(rootPostId, publishedDescendantPostId),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `activation rejects a graph whose descendant has no member parent in the same revision`() {
        val revision = stagingRevision()

        assertThatIllegalArgumentException().isThrownBy {
            PublicationPolicy.validateForActivation(
                revision = revision,
                members =
                    listOf(
                        PublicationMember(revision.id, rootPostId, parentPostId = null, depth = 0),
                        PublicationMember(revision.id, publishedDescendantPostId, unpublishedParentPostId, depth = 2),
                    ),
                availabilityByPostId =
                    mapOf(
                        rootPostId to published(rootPostId),
                        publishedDescendantPostId to published(publishedDescendantPostId),
                    ),
                renderablePostIds = setOf(rootPostId, publishedDescendantPostId),
            )
        }
    }

    @Test
    fun `activation rejects published members without a renderable snapshot`() {
        val revision = stagingRevision()

        assertThatIllegalArgumentException().isThrownBy {
            PublicationPolicy.validateForActivation(
                revision = revision,
                members = listOf(PublicationMember(revision.id, rootPostId, parentPostId = null, depth = 0)),
                availabilityByPostId = mapOf(rootPostId to published(rootPostId)),
                renderablePostIds = emptySet(),
            )
        }
    }

    @Test
    fun `activation rejects a member whose availability has not been confirmed`() {
        val revision = stagingRevision()

        assertThatIllegalArgumentException().isThrownBy {
            PublicationPolicy.validateForActivation(
                revision = revision,
                members = listOf(PublicationMember(revision.id, rootPostId, parentPostId = null, depth = 0)),
                availabilityByPostId = emptyMap(),
                renderablePostIds = emptySet(),
            )
        }
    }

    @Test
    fun `activation rejects a member whose depth skips its structural parent`() {
        val revision = stagingRevision()

        assertThatIllegalArgumentException().isThrownBy {
            PublicationPolicy.validateForActivation(
                revision = revision,
                members =
                    listOf(
                        PublicationMember(revision.id, rootPostId, parentPostId = null, depth = 0),
                        PublicationMember(revision.id, publishedDescendantPostId, rootPostId, depth = 2),
                    ),
                availabilityByPostId =
                    mapOf(
                        rootPostId to published(rootPostId),
                        publishedDescendantPostId to published(publishedDescendantPostId),
                    ),
                renderablePostIds = setOf(rootPostId, publishedDescendantPostId),
            )
        }
    }

    @Test
    fun `only a staging revision can be validated for activation`() {
        val revision = stagingRevision().activate()

        assertThatIllegalArgumentException().isThrownBy {
            PublicationPolicy.validateForActivation(
                revision = revision,
                members = listOf(PublicationMember(revision.id, rootPostId, parentPostId = null, depth = 0)),
                availabilityByPostId = mapOf(rootPostId to published(rootPostId)),
                renderablePostIds = setOf(rootPostId),
            )
        }
    }

    @Test
    fun `a publication activates its root and revision as one consistent pointer pair`() {
        val publication = BlogPublication(id = publicationId, rootPostId = null, activeRevisionId = null)

        assertThatCode { publication.activate(rootPostId, revisionId) }.doesNotThrowAnyException()
    }

    private fun stagingRevision() =
        PublicationRevision(
            id = revisionId,
            publicationId = publicationId,
            state = PublicationRevisionState.STAGING,
        )

    private fun published(postId: PostId) =
        PostAvailability(
            postId = postId,
            status = PostAvailabilityStatus.PUBLISHED,
            confirmedAt = Instant.parse("2026-08-25T00:00:00Z"),
        )

    private fun unpublished(postId: PostId) =
        PostAvailability(
            postId = postId,
            status = PostAvailabilityStatus.UNPUBLISHED,
            confirmedAt = Instant.parse("2026-08-25T00:00:00Z"),
        )
}
