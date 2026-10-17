package xyz.robinjoon.notionblog.blog.application.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.support.fixture.PostFixtures

class MediaRepairTargetTest {
    private val expired = PostFixtures.capturedAt.minusSeconds(1)

    @Test
    fun `the same source revision supplies a freshly signed URL for a stored file and keeps the others`() {
        val stored = PostFixtures.hosted("legacy.png", signature = "old", expiresAt = expired)
        val missingFromSource = PostFixtures.hosted("gone.png", signature = "old", expiresAt = expired)
        val fresh = PostFixtures.hosted("legacy.png", signature = "new")
        val target = target("legacy", stored, missingFromSource)

        val sources = target.sources(imported("legacy", fresh))

        assertThat(sources).containsExactlyInAnyOrder(fresh, missingFromSource)
    }

    @Test
    fun `a changed draft never supplies a URL even for the same file name`() {
        val stored = PostFixtures.hosted("legacy.png", signature = "old", expiresAt = expired)
        val fresh = PostFixtures.hosted("legacy.png", signature = "new")
        val target = target("legacy", stored)

        val sources = target.sources(imported("rev-2", fresh))

        assertThat(sources).containsExactly(stored)
    }

    private fun target(
        sourceRevision: String,
        vararg media: MediaSource.SourceHosted,
    ) = MediaRepairTarget(PostFixtures.revisionId(1), SourceRevision(sourceRevision), media.toSet())

    private fun imported(
        sourceRevision: String,
        file: MediaSource.SourceHosted,
    ) = ImportedRevision(
        PostFixtures.document("doc"),
        PostFixtures.properties(),
        SourceRevision(sourceRevision),
        BlockTree(listOf(PostFixtures.image("i", file))),
    )
}
