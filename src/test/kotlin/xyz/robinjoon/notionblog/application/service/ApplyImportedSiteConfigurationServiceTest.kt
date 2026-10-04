package xyz.robinjoon.notionblog.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.application.model.ResolvedPresentationAsset
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.site.PresentationTokens
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

class ApplyImportedSiteConfigurationServiceTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val refreshPolicy = RefreshPolicy(
        successInterval = Duration.ofMinutes(15),
        initialFailureDelay = Duration.ofMinutes(2),
        maximumFailureDelay = Duration.ofMinutes(30),
    )

    @Test
    fun `apply and record failure own transaction boundaries`() {
        assertThat(
            ApplyImportedSiteConfigurationService::class.memberFunctions
                .single { it.name == "apply" }
                .findAnnotation<Transactional>(),
        ).isNotNull()
        assertThat(
            ApplyImportedSiteConfigurationService::class.memberFunctions
                .single { it.name == "recordFailure" }
                .findAnnotation<Transactional>(),
        ).isNotNull()
    }

    @Test
    fun `first apply selects the explicit current trusted profile validates its exact assets and creates publication`() {
        val style = asset("base", 1)
        val script = asset("enhance", 2)
        val favicon = asset("favicon", 3)
        val profile = profile(PresentationProfileKey("selected"), styleSheets = listOf(style), scripts = listOf(script))
        val configurations = RecordingSiteConfigurationRepository().apply { currentProfile = profile }
        val publications = RecordingPublicationRepository()
        val states = RecordingSyncStateRepository()
        val assets = RecordingPresentationAssetCatalog().apply {
            exact[style] = descriptor(style)
            exact[script] = descriptor(script)
            current["favicon"] = ResolvedPresentationAsset(favicon, descriptor(favicon))
        }
        val publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000010"))

        val applied = service(configurations, publications, assets, states) { publicationId }
            .apply(imported(profileKey = PresentationProfileKey("selected"), faviconAssetKey = "favicon"))

        assertThat(applied.rootChanged).isTrue()
        assertThat(applied.configuration).isEqualTo(
            SiteConfiguration(
                publicationId = publicationId,
                rootDocument = document("root-1"),
                headerDocument = document("header"),
                footerDocument = document("footer"),
                metadata = SiteMetadata("My Blog", "Description", "ko-KR", favicon),
                presentationProfile = profile.reference(),
            ),
        )
        assertThat(publications.saved).containsExactly(BlogPublication(publicationId, null, null))
        assertThat(configurations.saved).containsExactly(applied.configuration)
        assertThat(assets.resolved).containsExactly(style, script)
        assertThat(assets.currentKeys).containsExactly("favicon")
        assertThat(configurations.currentProfileKeys).containsExactly(PresentationProfileKey("selected"))
        assertThat(configurations.savedProfiles).isEmpty()
        assertThat(states.saved).isEqualTo(
            SyncState(SyncTarget.SiteConfiguration, now, now.plus(Duration.ofMinutes(15)), 0, null),
        )
        assertThat(configurations.synchronizedAt).containsExactly(now)
    }

    @Test
    fun `apply passes its initial instant to configuration persistence and sync success`() {
        val initial = Instant.parse("2026-08-25T00:00:00Z")
        val later = initial.plusSeconds(1)
        val configurations = RecordingSiteConfigurationRepository().apply {
            currentProfile = profile(PresentationProfileKey("default"))
        }
        val states = RecordingSyncStateRepository()
        val service = ApplyImportedSiteConfigurationService(
            configurations,
            RecordingPublicationRepository(),
            RecordingPresentationAssetCatalog(),
            states,
            SequencedClock(initial, later),
            refreshPolicy,
            PresentationProfileKey("default"),
        ) { PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000015")) }

        service.apply(imported())

        assertThat(configurations.synchronizedAt).containsExactly(initial)
        assertThat(states.saved).isEqualTo(
            SyncState(SyncTarget.SiteConfiguration, initial, initial.plus(Duration.ofMinutes(15)), 0, null),
        )
    }

    @Test
    fun `invalid profile or exact profile asset leaves the existing configuration and publication untouched`() {
        val currentConfiguration = configuration(PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000011")))
        val configurations = RecordingSiteConfigurationRepository().apply {
            this.currentConfiguration = currentConfiguration
            currentProfile = profile(PresentationProfileKey("selected"), styleSheets = listOf(asset("missing", 1)))
        }
        val publications = RecordingPublicationRepository().apply {
            currentPublication = BlogPublication(currentConfiguration.publicationId, null, null)
        }
        val assets = RecordingPresentationAssetCatalog()

        assertThatIllegalArgumentException().isThrownBy {
            service(configurations, publications, assets) { error("must not create a publication") }
                .apply(imported(profileKey = PresentationProfileKey("selected")))
        }

        assertThat(configurations.saved).isEmpty()
        assertThat(publications.saved).isEmpty()
        assertThat(configurations.currentConfiguration).isEqualTo(currentConfiguration)
        assertThat(publications.currentPublication).isEqualTo(BlogPublication(currentConfiguration.publicationId, null, null))
    }

    @Test
    fun `an unregistered profile and malformed BCP47 tag are rejected before configuration is saved`() {
        val configurations = RecordingSiteConfigurationRepository().apply {
            currentProfile = profile(PresentationProfileKey("default"))
        }
        val publications = RecordingPublicationRepository()
        val assets = RecordingPresentationAssetCatalog()

        assertThatIllegalArgumentException().isThrownBy {
            service(configurations, publications, assets) { error("must not create a publication") }
                .apply(imported(profileKey = PresentationProfileKey("missing")))
        }
        assertThatIllegalArgumentException().isThrownBy {
            service(configurations, publications, assets) { error("must not create a publication") }
                .apply(imported(languageTag = "ko_KR"))
        }

        assertThat(configurations.saved).isEmpty()
        assertThat(publications.saved).isEmpty()
    }

    @Test
    fun `existing configuration reuses its publication when the imported root changes`() {
        val publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000012"))
        val old = configuration(publicationId, root = document("old-root"))
        val profile = profile(PresentationProfileKey("default"))
        val configurations = RecordingSiteConfigurationRepository().apply {
            currentConfiguration = old
            currentProfile = profile
        }
        val publications = RecordingPublicationRepository().apply {
            currentPublication = BlogPublication(publicationId, null, null)
        }

        val applied = service(configurations, publications, RecordingPresentationAssetCatalog()) {
            error("an existing configuration must keep its publication")
        }.apply(imported())

        assertThat(applied.rootChanged).isTrue()
        assertThat(applied.configuration.publicationId).isEqualTo(publicationId)
        assertThat(publications.saved).isEmpty()
    }

    @Test
    fun `unchanged root is reported without creating another publication`() {
        val publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000013"))
        val profile = profile(PresentationProfileKey("default"))
        val configurations = RecordingSiteConfigurationRepository().apply {
            currentConfiguration = configuration(publicationId)
            currentProfile = profile
        }
        val publications = RecordingPublicationRepository().apply {
            currentPublication = BlogPublication(publicationId, null, null)
        }

        val applied = service(configurations, publications, RecordingPresentationAssetCatalog()) {
            error("an existing configuration must keep its publication")
        }.apply(imported())

        assertThat(applied.rootChanged).isFalse()
        assertThat(publications.saved).isEmpty()
    }

    @Test
    fun `external failure only records the site configuration retry state`() {
        val publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000014"))
        val currentConfiguration = configuration(publicationId)
        val configurations = RecordingSiteConfigurationRepository().apply {
            this.currentConfiguration = currentConfiguration
        }
        val publications = RecordingPublicationRepository().apply {
            currentPublication = BlogPublication(publicationId, null, null)
        }
        val states = RecordingSyncStateRepository().apply {
            existing = SyncState(
                target = SyncTarget.SiteConfiguration,
                lastSuccessAt = now.minusSeconds(30),
                refreshAfter = now,
                failureCount = 1,
                lastErrorKind = SyncFailureKind.RETRYABLE_SOURCE,
            )
        }

        service(configurations, publications, RecordingPresentationAssetCatalog(), states) {
            error("failure recording must not create a publication")
        }.recordFailure(SyncFailureKind.ACCESS)

        assertThat(configurations.saved).isEmpty()
        assertThat(publications.saved).isEmpty()
        assertThat(configurations.currentConfiguration).isEqualTo(currentConfiguration)
        assertThat(publications.currentPublication).isEqualTo(BlogPublication(publicationId, null, null))
        assertThat(states.saved).isEqualTo(
            SyncState(
                target = SyncTarget.SiteConfiguration,
                lastSuccessAt = now.minusSeconds(30),
                refreshAfter = now.plus(Duration.ofMinutes(4)),
                failureCount = 2,
                lastErrorKind = SyncFailureKind.ACCESS,
            ),
        )
    }

    private fun service(
        configurations: SiteConfigurationRepository,
        publications: PublicationRepository,
        assets: PresentationAssetCatalog,
        syncStates: SyncStateRepository = RecordingSyncStateRepository(),
        publicationIdFactory: () -> PublicationId,
    ) = ApplyImportedSiteConfigurationService(
        configurations,
        publications,
        assets,
        syncStates,
        clock,
        refreshPolicy,
        PresentationProfileKey("default"),
        publicationIdFactory,
    )

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
    ) = SiteConfiguration(
        publicationId = publicationId,
        rootDocument = root,
        headerDocument = document("header"),
        footerDocument = document("footer"),
        metadata = SiteMetadata("Existing", null, "ko-KR", null),
        presentationProfile = profile(PresentationProfileKey("default")).reference(),
    )

    private fun profile(
        key: PresentationProfileKey,
        styleSheets: List<PresentationAssetRef> = emptyList(),
        scripts: List<PresentationAssetRef> = emptyList(),
    ) = PresentationProfile(
        id = PresentationProfileId(UUID.fromString("00000000-0000-0000-0000-000000000020")),
        key = key,
        version = 1,
        tokens = PresentationTokens(),
        styleSheets = styleSheets,
        scripts = scripts,
    )

    private fun PresentationProfile.reference() = xyz.robinjoon.notionblog.domain.site.PresentationProfileRef(id, version)

    private fun asset(key: String, version: Long) = PresentationAssetRef(key, version, "sha384-$key-$version")

    private fun descriptor(reference: PresentationAssetRef) = PresentationAssetDescriptor(
        publicPath = "/presentation/${reference.key}-${reference.version}",
        mediaType = "text/css",
        integrity = reference.integrity,
    )

    private fun document(externalId: String) = SourceDocumentRef(SourceId("notion-main"), externalId)

    private class RecordingSiteConfigurationRepository : SiteConfigurationRepository {
        var currentConfiguration: SiteConfiguration? = null
        var currentProfile: PresentationProfile? = null
        val saved = mutableListOf<SiteConfiguration>()
        val savedProfiles = mutableListOf<PresentationProfile>()
        val currentProfileKeys = mutableListOf<PresentationProfileKey>()

        override fun findCurrent(): SiteConfiguration? = currentConfiguration

        val synchronizedAt = mutableListOf<Instant>()

        override fun save(configuration: SiteConfiguration, synchronizedAt: Instant) {
            currentConfiguration = configuration
            saved += configuration
            this.synchronizedAt += synchronizedAt
        }

        override fun findProfile(reference: xyz.robinjoon.notionblog.domain.site.PresentationProfileRef): PresentationProfile? = null

        override fun findCurrentProfile(key: PresentationProfileKey): PresentationProfile? {
            currentProfileKeys += key
            return currentProfile
        }

        override fun saveProfile(profile: PresentationProfile, createdAt: Instant) {
            savedProfiles += profile
        }

        override fun activateProfile(reference: xyz.robinjoon.notionblog.domain.site.PresentationProfileRef) = Unit
    }

    private class RecordingPublicationRepository : PublicationRepository {
        var currentPublication: BlogPublication? = null
        val saved = mutableListOf<BlogPublication>()

        override fun findCurrent(): BlogPublication? = currentPublication

        override fun save(publication: BlogPublication) {
            currentPublication = publication
            saved += publication
        }

        override fun findRevision(revisionId: PublicationRevisionId): PublicationRevision? = null

        override fun findActiveRevision(publicationId: PublicationId): PublicationRevision? = null

        override fun findStagingRevisions(publicationId: PublicationId): List<PublicationRevision> = emptyList()

        override fun createRevision(revision: PublicationRevision, transitionedAt: Instant) = Unit

        override fun updateRevision(revision: PublicationRevision, transitionedAt: Instant) = Unit

        override fun saveMembers(revisionId: PublicationRevisionId, members: Collection<PublicationMember>) = Unit

        override fun findMembers(revisionId: PublicationRevisionId): List<PublicationMember> = emptyList()

        override fun findActiveMemberPostIds(
            publicationId: PublicationId,
            postIds: Set<xyz.robinjoon.notionblog.domain.post.PostId>,
        ): Set<xyz.robinjoon.notionblog.domain.post.PostId> = emptySet()

        override fun findActiveDirectChildren(
            publicationId: PublicationId,
            parentPostId: xyz.robinjoon.notionblog.domain.post.PostId,
        ): List<PublicationMember> = emptyList()
    }

    private class RecordingPresentationAssetCatalog : PresentationAssetCatalog {
        val exact = mutableMapOf<PresentationAssetRef, PresentationAssetDescriptor>()
        val current = mutableMapOf<String, ResolvedPresentationAsset>()
        val resolved = mutableListOf<PresentationAssetRef>()
        val currentKeys = mutableListOf<String>()

        override fun resolve(reference: PresentationAssetRef): PresentationAssetDescriptor? {
            resolved += reference
            return exact[reference]
        }

        override fun resolveCurrent(key: String): ResolvedPresentationAsset? {
            currentKeys += key
            return current[key]
        }
    }

    private class RecordingSyncStateRepository : SyncStateRepository {
        var existing: SyncState? = null
        var saved: SyncState? = null

        override fun delete(target: SyncTarget) {
            existing = existing?.takeUnless { it.target == target }
            saved = saved?.takeUnless { it.target == target }
        }

        override fun findDue(now: Instant, limit: Int): List<SyncState> = emptyList()

        override fun find(target: SyncTarget): SyncState? = existing

        override fun save(state: SyncState) {
            saved = state
        }
    }

    private class SequencedClock(
        private vararg val instants: Instant,
    ) : Clock() {
        private var index = 0

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = instants[index++]
    }
}
