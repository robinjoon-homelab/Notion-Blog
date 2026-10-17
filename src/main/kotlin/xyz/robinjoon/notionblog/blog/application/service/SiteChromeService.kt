package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.SiteChrome
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration

// 모든 페이지를 감싸는 사이트 틀이다. 헤더·푸터·레거시 루트 링크는 해당 글이 공개일 때만 나타난다.
@Transactional(readOnly = true)
class SiteChromeService(
    private val siteConfigurations: SiteConfigurationRepository,
    private val profiles: PresentationProfileRepository,
    private val assets: PresentationAssetCatalog,
    private val posts: PostRepository,
    private val publishedPosts: PublishedPostStore,
    private val snapshotFailures: SnapshotFailureReporter,
) {
    // 사이트 설정, 그 표현 프로필, 자산 가운데 하나라도 사용할 수 없으면 null이다.
    @Transactional(readOnly = true)
    fun load(): SiteChrome? {
        val site = siteConfigurations.findCurrent() ?: return null
        val profile = profiles.findProfile(site.presentationProfile) ?: return null
        val resolved = resolveAssets(site, profile.assets.styleSheets + profile.assets.scripts) ?: return null
        val bindings = posts.findBindingsBySourceDocuments(site.documents.all)
        return SiteChrome(
            site = site,
            presentation = profile,
            presentationAssets = resolved,
            header = fragment(bindings, site.documents.header),
            footer = fragment(bindings, site.documents.footer),
            rootPostId = bindings[site.documents.root]?.postId?.takeIf { publishedPosts.findPublicPostIds(setOf(it)).isNotEmpty() },
        )
    }

    // 사이트 문서는 블로그의 틀이므로 글 목록과 피드에서 제외한다.
    @Transactional(readOnly = true)
    fun siteDocumentPostIds(site: SiteConfiguration): Set<PostId> =
        posts.findBindingsBySourceDocuments(site.documents.all).values.mapTo(linkedSetOf()) { it.postId }

    private fun resolveAssets(
        site: SiteConfiguration,
        profileAssets: List<PresentationAssetRef>,
    ): Map<PresentationAssetRef, PresentationAssetDescriptor>? {
        val references = (profileAssets + listOfNotNull(site.metadata.favicon)).toSet()
        val resolved = references.associateWith { reference -> assets.resolve(reference) }
        if (resolved.values.any { it == null }) return null
        return resolved.mapValues { (_, descriptor) -> requireNotNull(descriptor) }
    }

    // 해석할 수 없는 조각은 페이지를 실패시키지 않고 제외한다.
    private fun fragment(
        bindings: Map<SourceDocumentRef, PostSourceBinding>,
        reference: SourceDocumentRef?,
    ): PostRevision? {
        val postId = reference?.let(bindings::get)?.postId ?: return null
        return try {
            publishedPosts.find(postId)?.revision
        } catch (failure: SnapshotContentException) {
            snapshotFailures.report(failure, SnapshotFailureOperation.POST_LOOKUP, postId)
            null
        }
    }
}
