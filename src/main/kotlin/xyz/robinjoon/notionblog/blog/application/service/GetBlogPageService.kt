package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.BlogPage
import xyz.robinjoon.notionblog.blog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetBlogPageUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration

@Transactional(readOnly = true)
class GetBlogPageService(
    private val publishedPosts: GetPublishedPostService,
    private val postRepository: PostRepository,
    private val siteConfigurations: SiteConfigurationRepository,
    private val presentationProfiles: PresentationProfileRepository,
    private val presentationAssets: PresentationAssetCatalog,
    private val links: ResolvePostLinksService,
) : GetBlogPageUseCase {
    @Transactional(readOnly = true)
    override fun getRoot(): BlogPageLookupResult = buildPage(publishedPosts.getRoot())

    @Transactional(readOnly = true)
    override fun get(postId: PostId): BlogPageLookupResult = buildPage(publishedPosts.get(postId))

    private fun buildPage(postResult: PostLookupResult): BlogPageLookupResult =
        when (postResult) {
            PostLookupResult.NotFound -> BlogPageLookupResult.NotFound
            PostLookupResult.ContentUnavailable -> BlogPageLookupResult.ContentUnavailable
            is PostLookupResult.Found -> buildPage(postResult)
        }

    private fun buildPage(found: PostLookupResult.Found): BlogPageLookupResult {
        val post = found.post
        val site = siteConfigurations.findCurrent() ?: return BlogPageLookupResult.ContentUnavailable
        val profile = presentationProfiles.findProfile(site.presentationProfile) ?: return BlogPageLookupResult.ContentUnavailable
        val assets =
            resolveAssets(site, profile.assets.styleSheets + profile.assets.scripts) ?: return BlogPageLookupResult.ContentUnavailable
        val header = findFragment(site.documents.header)
        val footer = findFragment(site.documents.footer)
        val trees = listOfNotNull(post, header, footer).map { it.content }

        return BlogPageLookupResult.Found(
            BlogPage(
                site = site,
                presentation = profile,
                presentationAssets = assets,
                post = post,
                header = header,
                footer = footer,
                links = links.resolve(site.publicationId, trees),
                firstPublishedAt = found.firstPublishedAt,
                lastModifiedAt = found.lastModifiedAt,
                root = found.root,
            ),
        )
    }

    private fun resolveAssets(
        site: SiteConfiguration,
        profileAssets: List<PresentationAssetRef>,
    ): Map<PresentationAssetRef, xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor>? {
        val references = (profileAssets + listOfNotNull(site.metadata.favicon)).toSet()
        val resolved = linkedMapOf<PresentationAssetRef, xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor>()
        for (reference in references) {
            val descriptor = presentationAssets.resolve(reference) ?: return null
            resolved[reference] = descriptor
        }
        return resolved
    }

    private fun findFragment(reference: SourceDocumentRef?): Post? {
        val binding = reference?.let(postRepository::findBinding) ?: return null
        return (publishedPosts.get(binding.postId) as? PostLookupResult.Found)?.post
    }
}
