package xyz.robinjoon.notionblog.domain.post

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.util.UUID

class PostSourceBindingTest {
    @Test
    fun `a source binding associates the internal post identity with one source document reference`() {
        val postId = PostId(UUID.fromString("27a3f37c-f408-4e1f-b11c-4069a669ecd7"))
        val reference = SourceDocumentRef(SourceId("notion-main"), "source-document-id")

        val binding = PostSourceBinding(postId, reference)

        assertThat(binding.postId).isEqualTo(postId)
        assertThat(binding.sourceDocument).isEqualTo(reference)
    }
}
