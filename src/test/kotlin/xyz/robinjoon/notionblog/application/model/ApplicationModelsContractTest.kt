package xyz.robinjoon.notionblog.application.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.net.URI
import java.time.Instant
import java.util.UUID

class ApplicationModelsContractTest {
    @Test
    fun `post lookup distinguishes not found from unavailable content`() {
        assertThat(PostLookupResult.NotFound).isNotEqualTo(PostLookupResult.ContentUnavailable)
        assertThat(BlogPageLookupResult.NotFound).isNotEqualTo(BlogPageLookupResult.ContentUnavailable)
        val post = Post(PostId(UUID.randomUUID()), "A post", BlockTree(emptyList()))

        assertThat(PostLookupResult.Found(post).post).isEqualTo(post)
    }

    @Test
    fun `link resolution keeps internal and external destinations source neutral`() {
        assertThat(LinkResolution.External(URI("https://example.com")).url)
            .isEqualTo(URI("https://example.com"))
        assertThat(LinkResolution.Unlinked).isSameAs(LinkResolution.Unlinked)
    }

    @Test
    fun `stored post carries only normalized post snapshot metadata`() {
        val post = Post(PostId(UUID.randomUUID()), "A post", BlockTree(emptyList()))
        val capturedAt = Instant.parse("2026-08-25T00:00:00Z")

        val stored = StoredPost(post, SourceRevision("opaque-revision"), capturedAt)

        assertThat(stored.post).isEqualTo(post)
        assertThat(stored.sourceRevision).isEqualTo(SourceRevision("opaque-revision"))
        assertThat(stored.capturedAt).isEqualTo(capturedAt)
    }

    @Test
    fun `applied site configuration reports whether the publication root changed`() {
        val configuration =
            SiteConfiguration(
                publicationId = PublicationId(UUID.randomUUID()),
                rootDocument = SourceDocumentRef(SourceId("notion-main"), "root"),
                headerDocument = null,
                footerDocument = null,
                metadata = SiteMetadata("Blog", null, "ko-KR", null),
                presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
            )

        val applied = AppliedSiteConfiguration(configuration, rootChanged = true)

        assertThat(applied.configuration).isEqualTo(configuration)
        assertThat(applied.rootChanged).isTrue()
    }
}
