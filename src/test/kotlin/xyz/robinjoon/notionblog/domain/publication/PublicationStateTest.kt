package xyz.robinjoon.notionblog.domain.publication

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.post.PostId
import java.util.UUID

class PublicationStateTest {
    private val publicationId = PublicationId(UUID.fromString("d2719cb7-bf66-4af3-a007-7fd249c1b414"))
    private val revisionId = PublicationRevisionId(UUID.fromString("e14a60a4-b2d3-40cc-94c4-c586e8bcc57e"))
    private val rootPostId = PostId(UUID.fromString("96bfdb45-7f5a-46fc-bf7e-e3d5495819b8"))

    @Test
    fun `an active publication always has both its root and active revision`() {
        assertThatIllegalArgumentException().isThrownBy {
            BlogPublication(publicationId, rootPostId, activeRevisionId = null)
        }
    }

    @Test
    fun `a staging revision can be activated then superseded but cannot be abandoned`() {
        val active = stagingRevision().activate()

        assertThat(active.state).isEqualTo(PublicationRevisionState.ACTIVE)
        assertThat(active.supersede().state).isEqualTo(PublicationRevisionState.SUPERSEDED)
        assertThatIllegalArgumentException().isThrownBy { active.abandon() }
    }

    @Test
    fun `only an unactivated staging revision can be abandoned`() {
        assertThat(stagingRevision().abandon().state).isEqualTo(PublicationRevisionState.ABANDONED)
        assertThatIllegalArgumentException().isThrownBy { stagingRevision().supersede() }
    }

    private fun stagingRevision() =
        PublicationRevision(
            id = revisionId,
            publicationId = publicationId,
            state = PublicationRevisionState.STAGING,
        )
}
