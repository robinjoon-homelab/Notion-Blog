package xyz.robinjoon.notionblog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.BlogPage
import xyz.robinjoon.notionblog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.application.model.PostLookupResult
import xyz.robinjoon.notionblog.application.port.input.GetBlogPageUseCase
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef

@Transactional(readOnly = true)
class GetBlogPageService(
    private val publishedPosts: GetPublishedPostService,
    private val postRepository: PostRepository,
    private val siteConfigurations: SiteConfigurationRepository,
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
            is PostLookupResult.Found -> buildPage(postResult.post)
        }

    private fun buildPage(post: Post): BlogPageLookupResult {
        val site = siteConfigurations.findCurrent() ?: return BlogPageLookupResult.ContentUnavailable
        val profile = siteConfigurations.findProfile(site.presentationProfile) ?: return BlogPageLookupResult.ContentUnavailable
        val assets = resolveAssets(site, profile.styleSheets + profile.scripts) ?: return BlogPageLookupResult.ContentUnavailable
        val header = findFragment(site.headerDocument)
        val footer = findFragment(site.footerDocument)
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
            ),
        )
    }

    private fun resolveAssets(
        site: SiteConfiguration,
        profileAssets: List<PresentationAssetRef>,
    ): Map<PresentationAssetRef, xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor>? {
        val references = (profileAssets + listOfNotNull(site.metadata.favicon)).toSet()
        val resolved = linkedMapOf<PresentationAssetRef, xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor>()
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
