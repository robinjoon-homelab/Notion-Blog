package xyz.robinjoon.notionblog.blog.domain

import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class SourceIdentityTest {
    @Test
    fun `source identity values cannot be blank`() {
        assertThatIllegalArgumentException().isThrownBy { SourceId(" ") }
        assertThatIllegalArgumentException().isThrownBy { SourceDocumentRef(SourceId("notion-main"), " ") }
        assertThatIllegalArgumentException().isThrownBy { SourceRevision(" ") }
    }
}
