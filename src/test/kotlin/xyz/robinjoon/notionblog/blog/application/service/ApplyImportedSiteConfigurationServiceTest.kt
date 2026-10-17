package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.ResolvedPresentationAsset
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssets
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationTokens
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// The resolver is the real application collaborator, so profile, asset, and language validation run as in production.
class ApplyImportedSiteConfigurationServiceTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val refreshPolicy =
        RefreshPolicy(
            successInterval = Duration.ofMinutes(15),
            initialFailureDelay = Duration.ofMinutes(2),
            maximumFailureDelay = Duration.ofMinutes(30),
        )
    private val configurations = mockk<SiteConfigurationRepository>()
    private val profiles = mockk<PresentationProfileRepository>()
    private val publications = mockk<PublicationRepository>()
    private val assets = mockk<PresentationAssetCatalog>()
    private val syncStates = mockk<SyncStateRepository>()
    private val registrations = mockk<RegisterPostService>()
    private val newPublicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000099"))

    @Test
    fun `first apply selects the explicit current trusted profile validates its exact assets and assigns a new publication`() {
        val style = asset("base", 1)
        val script = asset("enhance", 2)
        val favicon = asset("favicon", 3)
        val profile = profile(PresentationProfileKey("selected"), styleSheets = listOf(style), scripts = listOf(script))
        val publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
        every { configurations.findCurrent() } returns null
        every { profiles.findCurrentProfile(PresentationProfileKey("selected")) } returns profile
        every { assets.resolve(style) } returns descriptor(style)
        every { assets.resolve(script) } returns descriptor(script)
        every { assets.resolveCurrent("favicon") } returns ResolvedPresentationAsset(favicon, descriptor(favicon))
        every { publications.save(any()) } just Runs
        givenWrites()

        val applied =
            service(resolver { publicationId })
                .apply(imported(profileKey = PresentationProfileKey("selected"), faviconAssetKey = "favicon"))

        assertThat(applied).isEqualTo(
            SiteConfiguration.restore(
                publicationId = publicationId,
                documents = SiteDocuments(root = document("root-1"), header = document("header"), footer = document("footer")),
                metadata = SiteMetadata("My Blog", "Description", "ko-KR", favicon),
                presentationProfile = profile.reference,
            ),
        )
    }

    @Test
    fun `a selected profile whose exact asset is missing is rejected`() {
        val currentConfiguration = configuration(PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000011")))
        val missing = asset("missing", 1)
        every { configurations.findCurrent() } returns currentConfiguration
        every { profiles.findCurrentProfile(PresentationProfileKey("selected")) } returns
            profile(PresentationProfileKey("selected"), styleSheets = listOf(missing))
        every { assets.resolve(missing) } returns null

        assertThatIllegalArgumentException().isThrownBy {
            service(resolver { newPublicationId })
                .apply(imported(profileKey = PresentationProfileKey("selected")))
        }
    }

    @Test
    fun `an unregistered profile and malformed BCP47 tag are rejected`() {
        every { configurations.findCurrent() } returns null
        every { profiles.findCurrentProfile(PresentationProfileKey("missing")) } returns null
        every { profiles.findCurrentProfile(PresentationProfileKey("default")) } returns profile(PresentationProfileKey("default"))
        val service = service(resolver { newPublicationId })

        assertThatIllegalArgumentException().isThrownBy {
            service.apply(imported(profileKey = PresentationProfileKey("missing")))
        }
        assertThatIllegalArgumentException().isThrownBy {
            service.apply(imported(languageTag = "ko_KR"))
        }
    }

    @Test
    fun `existing configuration reuses its publication when the imported root changes`() {
        val publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000012"))
        every { configurations.findCurrent() } returns configuration(publicationId, root = document("old-root"))
        every { profiles.findCurrentProfile(PresentationProfileKey("default")) } returns profile(PresentationProfileKey("default"))
        givenWrites()

        val applied = service(resolver { newPublicationId }).apply(imported())

        assertThat(applied.publicationId).isEqualTo(publicationId)
        assertThat(applied.documents.root).isEqualTo(document("root-1"))
    }

    @Test
    fun `a site without the legacy root document is accepted`() {
        val publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000013"))
        every { configurations.findCurrent() } returns configuration(publicationId)
        every { profiles.findCurrentProfile(PresentationProfileKey("default")) } returns profile(PresentationProfileKey("default"))
        givenWrites()

        val applied = service(resolver { newPublicationId }).apply(imported().copy(rootDocument = null))

        assertThat(applied.documents.root).isNull()
    }

    private fun givenWrites() {
        every { configurations.save(any(), any()) } just Runs
        every { registrations.registerSourceDocuments(any()) } just Runs
        every { syncStates.find(SyncTarget.SiteConfiguration) } returns null
        every { syncStates.save(any()) } just Runs
    }

    private fun service(resolver: ImportedSiteConfigurationResolver) =
        ApplyImportedSiteConfigurationService(
            configurations,
            publications,
            resolver,
            SyncStateRecorder(syncStates, refreshPolicy),
            registrations,
            clock,
        )

    private fun resolver(publicationIdFactory: () -> PublicationId) =
        ImportedSiteConfigurationResolver(profiles, assets, PresentationProfileKey("default"), publicationIdFactory)

    private fun imported(
        profileKey: PresentationProfileKey? = null,
        languageTag: String = "ko-KR",
        faviconAssetKey: String? = null,
    ) = ImportedSiteConfiguration(
        rootDocument = document("root-1"),
        headerDocument = document("header"),
        footerDocument = document("footer"),
        metadata = ImportedSiteMetadata("My Blog", "Description", languageTag, faviconAssetKey),
        presentationProfileKey = profileKey,
    )

    private fun configuration(
        publicationId: PublicationId,
        root: SourceDocumentRef = document("root-1"),
    ) = SiteConfiguration.restore(
        publicationId = publicationId,
        documents = SiteDocuments(root = root, header = document("header"), footer = document("footer")),
        metadata = SiteMetadata("Existing", null, "ko-KR", null),
        presentationProfile = profile(PresentationProfileKey("default")).reference,
    )

    private fun profile(
        key: PresentationProfileKey,
        styleSheets: List<PresentationAssetRef> = emptyList(),
        scripts: List<PresentationAssetRef> = emptyList(),
    ) = PresentationProfile.restore(
        reference = PresentationProfileRef(PresentationProfileId(UUID.fromString("00000000-0000-0000-0000-000000000020")), 1),
        key = key,
        tokens = PresentationTokens(),
        assets = PresentationAssets(styleSheets = styleSheets, scripts = scripts),
    )

    private fun asset(
        key: String,
        version: Long,
    ) = PresentationAssetRef(key, version, "sha384-$key-$version")

    private fun descriptor(reference: PresentationAssetRef) =
        PresentationAssetDescriptor(
            publicPath = "/presentation/${reference.key}-${reference.version}",
            mediaType = "text/css",
            integrity = reference.integrity,
        )

    private fun document(externalId: String) = SourceDocumentRef(SourceId("notion-main"), externalId)
}
