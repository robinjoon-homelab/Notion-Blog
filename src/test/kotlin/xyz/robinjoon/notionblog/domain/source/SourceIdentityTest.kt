package xyz.robinjoon.notionblog.domain.source

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class SourceIdentityTest {
    @Test
    fun `source document reference preserves an adapter-validated opaque external identifier`() {
        val reference =
            SourceDocumentRef(
                sourceId = SourceId("notion-main"),
                externalId = "adapter-validated-document-token",
            )

        assertThat(reference.sourceId).isEqualTo(SourceId("notion-main"))
        assertThat(reference.externalId).isEqualTo("adapter-validated-document-token")
    }

    @Test
    fun `source revision compares only opaque equality without inferring an order`() {
        val revision = SourceRevision("opaque-revision-token")

        assertThat(revision).isEqualTo(SourceRevision("opaque-revision-token"))
        assertThat(revision).isNotEqualTo(SourceRevision("another-opaque-revision-token"))
    }

    @Test
    fun `source identity values cannot be blank`() {
        assertThatIllegalArgumentException().isThrownBy { SourceId(" ") }
        assertThatIllegalArgumentException().isThrownBy { SourceDocumentRef(SourceId("notion-main"), " ") }
        assertThatIllegalArgumentException().isThrownBy { SourceRevision(" ") }
    }
}
