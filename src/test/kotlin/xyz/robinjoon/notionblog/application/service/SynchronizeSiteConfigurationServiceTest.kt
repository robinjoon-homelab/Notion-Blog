package xyz.robinjoon.notionblog.application.service

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import xyz.robinjoon.notionblog.application.model.AppliedSiteConfiguration
import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

class SynchronizeSiteConfigurationServiceTest {
    private val source = mockk<SiteConfigurationSource>()
    private val applyService = mockk<ApplyImportedSiteConfigurationService>()
    private val publicationService = mockk<SynchronizePublicationService>()
    private val service = SynchronizeSiteConfigurationService(source, applyService, publicationService)

    @Test
    fun `fetches outside a transaction applies then synchronizes publication only when root changes`() {
        val imported = imported()
        every { source.fetch() } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            imported
        }
        every { applyService.apply(imported) } returns applied(rootChanged = true)
        every { publicationService.synchronize() } just runs

        service.synchronize()

        verify(exactly = 1) { applyService.apply(imported) }
        verify(exactly = 1) { publicationService.synchronize() }

        every { applyService.apply(imported) } returns applied(rootChanged = false)
        service.synchronize()

        verify(exactly = 2) { applyService.apply(imported) }
        verify(exactly = 1) { publicationService.synchronize() }
    }

    @Test
    fun `records classified fetch and validation failures without applying imported settings`() {
        val sourceFailure = SourceAuthenticationException()
        every { source.fetch() } throws sourceFailure
        every { applyService.recordFailure(any()) } just runs

        assertThatThrownBy { service.synchronize() }.isSameAs(sourceFailure)
        verify(exactly = 1) { applyService.recordFailure(SyncFailureKind.AUTHENTICATION) }
        verify(exactly = 0) { applyService.apply(any()) }

        val imported = imported()
        every { source.fetch() } returns imported
        every { applyService.apply(imported) } throws IllegalArgumentException()

        assertThatThrownBy { service.synchronize() }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 1) { applyService.recordFailure(SyncFailureKind.CONFIGURATION) }
        verify(exactly = 0) { publicationService.synchronize() }
    }

    @Test
    fun `does not record a site failure after publication synchronization fails`() {
        val imported = imported()
        val publicationFailure = SourceMappingException()
        every { source.fetch() } returns imported
        every { applyService.apply(imported) } returns applied(rootChanged = true)
        every { publicationService.synchronize() } throws publicationFailure

        assertThatThrownBy { service.synchronize() }.isSameAs(publicationFailure)

        verify(exactly = 0) { applyService.recordFailure(any()) }
    }

    @Test
    fun `does not declare a transaction boundary`() {
        assertThat(SynchronizeSiteConfigurationService::class.findAnnotation<Transactional>()).isNull()
        assertThat(SynchronizeSiteConfigurationService::class.memberFunctions)
            .noneMatch { it.findAnnotation<Transactional>() != null }
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
                SiteConfiguration(
                    publicationId = PublicationId(UUID.randomUUID()),
                    rootDocument = sourceDocument("root"),
                    headerDocument = null,
                    footerDocument = null,
                    metadata = SiteMetadata("Blog", null, "ko-KR", null),
                    presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
                ),
            rootChanged = rootChanged,
        )

    private fun sourceDocument(externalId: String) = SourceDocumentRef(SourceId("notion-main"), externalId)
}
