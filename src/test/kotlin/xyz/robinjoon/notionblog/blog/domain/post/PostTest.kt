package xyz.robinjoon.notionblog.blog.domain.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Instant

class PostTest {
    private val postId = PostFixtures.postId(1)
    private val first = PostFixtures.revisionId(1)
    private val second = PostFixtures.revisionId(2)
    private val firstPublication = Instant.parse("2026-10-16T01:00:00Z")
    private val laterPublication = Instant.parse("2026-10-17T01:00:00Z")

    @Test
    fun `a registered post is private without revisions or publication history`() {
        val post = Post.register(postId, PostFixtures.document("doc"))

        assertThat(post.latestRevisionId).isNull()
        assertThat(post.publication).isEqualTo(PostPublication(PostVisibility.PRIVATE, null, false, 0, null))
        assertThat(post.firstPublishedAt).isNull()
    }

    @Test
    fun `recording a revision changes only the latest draft`() {
        val published = PostFixtures.publicPost(postId, published = first, generation = 3)

        val updated = published.recordRevision(second)

        assertThat(updated.latestRevisionId).isEqualTo(second)
        assertThat(updated.publication).isEqualTo(published.publication)
    }

    @Test
    fun `publishing records the first publication once across unpublish and later publications`() {
        val draft = Post.register(postId, PostFixtures.document("doc")).recordRevision(first)

        val published = draft.publish(first, firstPublication)
        val republished = published.unpublish().recordRevision(second).publish(second, laterPublication)

        assertThat(published.publication.isPublic).isTrue()
        assertThat(published.firstPublishedAt).isEqualTo(firstPublication)
        assertThat(republished.publication.publishedRevisionId).isEqualTo(second)
        assertThat(republished.firstPublishedAt).isEqualTo(firstPublication)
    }

    @Test
    fun `every admin command advances the generation`() {
        val draft = PostFixtures.privatePost(postId, latest = first, generation = 4)

        val published = draft.publish(first, firstPublication)
        val unpublished = published.unpublish()
        val unpublishedAgain = unpublished.unpublish()
        val archived = unpublishedAgain.archive()
        val restored = archived.unarchive()

        assertThat(listOf(published, unpublished, unpublishedAgain, archived, restored).map { it.publication.generation })
            .containsExactly(5L, 6L, 7L, 8L, 9L)
    }

    @Test
    fun `unpublishing keeps the published revision and first publication for history`() {
        val unpublished = PostFixtures.publicPost(postId, published = first).unpublish()

        assertThat(unpublished.publication.visibility).isEqualTo(PostVisibility.PRIVATE)
        assertThat(unpublished.publication.publishedRevisionId).isEqualTo(first)
        assertThat(unpublished.firstPublishedAt).isEqualTo(PostFixtures.capturedAt)
    }

    @Test
    fun `archiving makes a public post private and restoring keeps it private`() {
        val archived = PostFixtures.publicPost(postId, published = first).archive()
        val restored = archived.unarchive()

        assertThat(archived.publication.archived).isTrue()
        assertThat(archived.publication.isPublic).isFalse()
        assertThat(restored.publication.archived).isFalse()
        assertThat(restored.publication.isPublic).isFalse()
    }

    @Test
    fun `only the latest revision of an unarchived post can be published`() {
        val draft = PostFixtures.privatePost(postId, latest = second)

        assertThatIllegalArgumentException()
            .isThrownBy { draft.publish(first, firstPublication) }
            .withMessage("only the latest revision can be published")
        assertThatIllegalArgumentException()
            .isThrownBy { draft.archive().publish(second, firstPublication) }
            .withMessage("an archived post cannot be published")
    }

    @Test
    fun `a publication state that readers could see without a revision or date is rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy { PostPublication(PostVisibility.PUBLIC, null, false, 0, firstPublication) }
        assertThatIllegalArgumentException()
            .isThrownBy { PostPublication(PostVisibility.PUBLIC, first, false, 0, null) }
        assertThatIllegalArgumentException()
            .isThrownBy { PostPublication(PostVisibility.PUBLIC, first, true, 0, firstPublication) }
        assertThatIllegalArgumentException()
            .isThrownBy { PostPublication(PostVisibility.PRIVATE, null, false, -1, null) }
    }
}
