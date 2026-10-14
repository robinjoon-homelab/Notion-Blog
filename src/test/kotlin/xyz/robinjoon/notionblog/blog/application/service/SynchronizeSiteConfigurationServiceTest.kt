package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.AppliedSiteConfiguration
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.blog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import java.util.UUID

class SynchronizeSiteConfigurationServiceTest {
    private val source = mockk<SiteConfigurationSource>()
    private val applyService = mockk<ApplyImportedSiteConfigurationService>()
    private val publicationService = mockk<SynchronizePublicationService>()
    private val service = SynchronizeSiteConfigurationService(source, applyService, publicationService)

    @Test
    fun `propagates fetch and validation failures`() {
        val sourceFailure = SourceAuthenticationException()
        every { source.fetch() } throws sourceFailure
        every { applyService.recordFailure(any()) } just runs

        assertThatThrownBy { service.synchronize() }.isSameAs(sourceFailure)

        val imported = imported()
        every { source.fetch() } returns imported
        every { applyService.apply(imported) } throws IllegalArgumentException()

        assertThatThrownBy { service.synchronize() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `propagates a publication synchronization failure`() {
        val imported = imported()
        val publicationFailure = SourceMappingException()
        every { source.fetch() } returns imported
        every { applyService.apply(imported) } returns applied(rootChanged = true)
        every { publicationService.synchronize() } throws publicationFailure

        assertThatThrownBy { service.synchronize() }.isSameAs(publicationFailure)
    }

    private fun imported(): ImportedSiteConfiguration =
        ImportedSiteConfiguration(
            rootDocument = sourceDocument("root"),
            headerDocument = null,
            footerDocument = null,
            metadata = ImportedSiteMetadata("Blog", null, "ko-KR", null),
            presentationProfileKey = null,
        )

    private fun applied(rootChanged: Boolean): AppliedSiteConfiguration =
        AppliedSiteConfiguration(
            configuration =
                SiteConfiguration.restore(
                    publicationId = PublicationId(UUID.randomUUID()),
                    documents = SiteDocuments(root = sourceDocument("root"), header = null, footer = null),
                    metadata = SiteMetadata("Blog", null, "ko-KR", null),
                    presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
                ),
            rootChanged = rootChanged,
        )

    private fun sourceDocument(externalId: String) = SourceDocumentRef(SourceId("notion-main"), externalId)
}
