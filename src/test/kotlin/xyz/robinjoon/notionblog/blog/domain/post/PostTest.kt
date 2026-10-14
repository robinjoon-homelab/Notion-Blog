package xyz.robinjoon.notionblog.blog.domain.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import java.util.UUID

class PostTest {
    @Test
    fun `accepts an empty block tree for a post with no body`() {
        val post = Post.restore(PostId(UUID.randomUUID()), "A title", BlockTree(emptyList()))

        assertThat(post.content.roots).isEmpty()
    }

    @Test
    fun `rejects a title that is blank after normalization`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                Post.restore(PostId(UUID.randomUUID()), " \t\n ", BlockTree(emptyList()))
            }.withMessage("post title must not be blank")
    }
}
