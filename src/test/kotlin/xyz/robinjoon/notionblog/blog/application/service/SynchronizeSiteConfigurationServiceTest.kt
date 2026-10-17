package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.blog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId

class SynchronizeSiteConfigurationServiceTest {
    private val source = mockk<SiteConfigurationSource>()
    private val applyService = mockk<ApplyImportedSiteConfigurationService>()
    private val service = SynchronizeSiteConfigurationService(source, applyService)

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

    private fun imported(): ImportedSiteConfiguration =
        ImportedSiteConfiguration(
            rootDocument = sourceDocument("root"),
            headerDocument = null,
            footerDocument = null,
            metadata = ImportedSiteMetadata("Blog", null, "ko-KR", null),
            presentationProfileKey = null,
        )

    private fun sourceDocument(externalId: String) = SourceDocumentRef(SourceId("notion-main"), externalId)
}
