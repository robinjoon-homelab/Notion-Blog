package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.support.fixture.PostFixtures

class ContentReferencesServiceTest {
    private val links = mockk<ResolvePostLinksService>()
    private val posts = mockk<PostRepository>()
    private val service = ContentReferencesService(links, posts)

    @Test
    fun `the same source file name resolves to the copy of the revision that shows it`() {
        val article = PostFixtures.revision(PostFixtures.postId(1), PostFixtures.revisionId(1))
        val header = PostFixtures.revision(PostFixtures.postId(2), PostFixtures.revisionId(2))
        val file = PostFixtures.hosted("logo.png")
        val name = SourceMediaKey.of(file.url)
        every { links.resolve(listOf(article.content, header.content)) } returns emptyMap()
        every { posts.findMediaBindings(setOf(article.id, header.id)) } returns
            mapOf(article.id to mapOf(name to key('a')), header.id to mapOf(name to key('b')))

        val references = service.resolve(listOf(article, header))

        assertThat(references.media.keyFor(article.id, file)).isEqualTo(key('a'))
        assertThat(references.media.keyFor(header.id, file)).isEqualTo(key('b'))
        assertThat(references.media.keyFor(PostFixtures.revisionId(3), file)).isNull()
    }

    private fun key(digit: Char) = MediaKey(digit.toString().repeat(64))
}
