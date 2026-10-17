package xyz.robinjoon.notionblog.blog.domain.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.net.URI

class PostRevisionTest {
    @Test
    fun `source-hosted files of a revision include the body and the cover but not external files`() {
        val body = PostFixtures.hosted("body.png")
        val cover = PostFixtures.hosted("cover.png")
        val revision =
            PostRevision(
                id = PostFixtures.revisionId(1),
                postId = PostFixtures.postId(1),
                properties = PostProperties("Title", null, emptyList(), cover),
                content =
                    BlockTree(
                        listOf(
                            PostFixtures.image("hosted", body),
                            PostFixtures.image("external", MediaSource.External(URI("https://example.com/x.png"))),
                        ),
                    ),
                sourceRevision = SourceRevision("rev"),
                capturedAt = PostFixtures.capturedAt,
            )

        assertThat(sourceHostedMedia(revision.properties, revision.content)).containsExactlyInAnyOrder(body, cover)
    }

    @Test
    fun `properties reject blank titles and summaries and blank or repeated tags`() {
        assertThatIllegalArgumentException().isThrownBy { PostProperties(" ", null, emptyList(), null) }
        assertThatIllegalArgumentException().isThrownBy { PostProperties("Title", " ", emptyList(), null) }
        assertThatIllegalArgumentException().isThrownBy { PostProperties("Title", null, listOf(" "), null) }
        assertThatIllegalArgumentException().isThrownBy { PostProperties("Title", null, listOf("a", "a"), null) }
    }

    @Test
    fun `properties keep the validated tags when the caller changes the input list afterwards`() {
        val input = mutableListOf("kotlin")
        val properties = PostProperties("Title", null, input, null)

        input.add(" ")
        input.add("kotlin")

        assertThat(properties.tags).containsExactly("kotlin")
    }
}
