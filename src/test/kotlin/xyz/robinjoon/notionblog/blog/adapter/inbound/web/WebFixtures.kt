package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.test.web.servlet.MvcResult
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesNavigation
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostLink
import xyz.robinjoon.notionblog.blog.application.model.SiteChrome
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssets
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationTokens
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import java.time.Instant
import java.util.UUID

// 웹 어댑터 테스트가 함께 쓰는 실제 값 객체 조립. 서비스 규칙은 여기서 흉내 내지 않는다.
internal object WebFixtures {
    val postId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
    val revisionId = PostRevisionId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    val capturedAt: Instant = Instant.parse("2026-10-05T03:04:05Z")

    fun revision(
        title: String = "Post",
        nodes: List<BlockNode> = emptyList(),
        properties: PostProperties = PostProperties(title, null, emptyList(), null),
        postId: PostId = WebFixtures.postId,
    ): PostRevision =
        PostRevision(
            id = revisionId,
            postId = postId,
            properties = PostProperties(title, properties.summary, properties.tags, properties.cover),
            content = BlockTree(nodes),
            sourceRevision = SourceRevision("rev-1"),
            capturedAt = capturedAt,
        )

    fun chrome(
        header: PostRevision? = null,
        footer: PostRevision? = null,
    ): SiteChrome {
        val style = PresentationAssetRef("style", 1, "sha256-style")
        val profile =
            PresentationProfile.restore(
                reference = PresentationProfileRef(PresentationProfileId(UUID.fromString("00000000-0000-0000-0000-000000000001")), 1),
                key = PresentationProfileKey("default"),
                tokens = PresentationTokens(),
                assets = PresentationAssets(styleSheets = listOf(style), scripts = emptyList()),
            )
        return SiteChrome(
            site =
                SiteConfiguration.restore(
                    publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                    documents = SiteDocuments(root = SourceDocumentRef(SourceId("notion"), "root"), header = null, footer = null),
                    metadata = SiteMetadata("Blog", "Description", "en", null),
                    presentationProfile = profile.reference,
                ),
            presentation = profile,
            presentationAssets = mapOf(style to PresentationAssetDescriptor("/presentation/notion.css", "text/css", style.integrity)),
            header = header,
            footer = footer,
        )
    }

    fun publicPage(
        revision: PostRevision,
        references: ContentReferences = ContentReferences(emptyMap(), RevisionMedia.empty),
        chrome: SiteChrome = chrome(),
    ): PublicPostPage = PublicPostPage(chrome, PublishedPost(revision, capturedAt), references)

    fun listPage(entries: List<PostListEntry> = emptyList()): PostListPage =
        PostListPage(chrome(), ResultPage.of(entries, PageQuery(1, 10)), ContentReferences(emptyMap(), RevisionMedia.empty))

    val seriesId = SeriesId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))

    fun seriesPostId(index: Int): PostId = PostId(UUID.nameUUIDFromBytes("series-post-$index".toByteArray()))

    // 공개 글 3개짜리 시리즈에서 currentIndex번째 글을 보고 있는 탐색 값이다. 제목은 이스케이프 확인을 위해 꺾쇠를 쓴다.
    fun seriesNavigation(currentIndex: Int): PublicSeriesNavigation =
        PublicSeriesNavigation(
            seriesId,
            "<DDD> 적용기",
            (0..2).map { SeriesPostLink(seriesPostId(it), "Chapter ${it + 1}") },
            seriesPostId(currentIndex),
        )

    fun links(resolutions: Map<LinkTarget.SourceDocument, xyz.robinjoon.notionblog.blog.application.model.LinkResolution>) =
        ContentReferences(resolutions, RevisionMedia.empty)
}

// 컨트롤러가 뷰에 넘긴 모델 값을 꺼낸다. 값이 없으면 테스트가 바로 실패한다.
internal inline fun <reified T : Any> MvcResult.modelOf(name: String): T {
    val value = checkNotNull(modelAndView).model[name]
    return value as? T ?: error("model attribute $name is ${value?.javaClass}")
}
