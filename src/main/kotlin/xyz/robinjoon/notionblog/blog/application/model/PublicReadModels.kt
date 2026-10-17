package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import java.time.Instant

data class PublishedPost(
    val revision: PostRevision,
    val firstPublishedAt: Instant,
)

// revisionId는 항목이 보여 주는 게시 리비전이며, 커버도 그 리비전을 통해서만 찾는다.
data class PostListEntry(
    val postId: PostId,
    val revisionId: PostRevisionId,
    val properties: PostProperties,
    val firstPublishedAt: Instant,
)

data class SiteChrome(
    val site: SiteConfiguration,
    val presentation: PresentationProfile,
    val presentationAssets: Map<PresentationAssetRef, PresentationAssetDescriptor>,
    val header: PostRevision?,
    val footer: PostRevision?,
    val rootPostId: PostId?,
)

// 링크는 공개 글일 때만 내부 페이지로 연결한다. media는 페이지에 보이는 리비전들의 저장 사본을 담는다.
data class ContentReferences(
    val links: Map<LinkTarget.SourceDocument, LinkResolution>,
    val media: RevisionMedia,
)

// 리비전별 저장 사본이다. 두 리비전이 같은 원본 파일 이름으로 서로 다른 바이트를 보여 줄 수 있으므로, 파일은 그 파일을 보여 주는
// 리비전을 통해서만 찾는다.
data class RevisionMedia(
    val byRevision: Map<PostRevisionId, Map<SourceMediaKey, MediaKey>>,
) {
    fun keyFor(
        revisionId: PostRevisionId,
        source: MediaSource.SourceHosted,
    ): MediaKey? = byRevision[revisionId]?.get(SourceMediaKey.of(source.url))

    operator fun plus(other: RevisionMedia): RevisionMedia = RevisionMedia(byRevision + other.byRevision)

    companion object {
        val empty = RevisionMedia(emptyMap())
    }
}

data class PublicPostPage(
    val chrome: SiteChrome,
    val post: PublishedPost,
    val references: ContentReferences,
)

data class PostListPage(
    val chrome: SiteChrome,
    val entries: ResultPage<PostListEntry>,
    val references: ContentReferences,
)

data class PublicFeed(
    val metadata: SiteMetadata,
    val entries: List<PublishedPost>,
)

sealed interface PublicPostLookupResult {
    data class Found(
        val page: PublicPostPage,
    ) : PublicPostLookupResult

    data object NotFound : PublicPostLookupResult

    data object ContentUnavailable : PublicPostLookupResult
}

sealed interface PostListLookupResult {
    data class Found(
        val page: PostListPage,
    ) : PostListLookupResult

    data object ContentUnavailable : PostListLookupResult
}

sealed interface PublicFeedLookupResult {
    data class Found(
        val feed: PublicFeed,
    ) : PublicFeedLookupResult

    data object ContentUnavailable : PublicFeedLookupResult
}
