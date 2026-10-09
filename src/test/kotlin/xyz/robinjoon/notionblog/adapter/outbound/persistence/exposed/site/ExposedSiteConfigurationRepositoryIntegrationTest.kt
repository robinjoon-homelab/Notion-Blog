package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.site

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.ExposedSiteConfigurationRepository
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PresentationProfileTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.SiteConfigurationTable
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationColorMode
import xyz.robinjoon.notionblog.domain.site.PresentationContentWidth
import xyz.robinjoon.notionblog.domain.site.PresentationDensity
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.PresentationTokens
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedSiteConfigurationRepositoryIntegrationTest {
    private val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private val database: Database by lazy {
        Database.connect(container.jdbcUrl, driver = "org.postgresql.Driver", user = container.username, password = container.password)
    }
    private val repository = ExposedSiteConfigurationRepository()

    @BeforeAll
    fun migrate() {
        container.start()
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopDatabase() {
        container.stop()
    }

    @BeforeEach
    fun clearDatabase() {
        connection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "truncate table sync_state, site_configuration, presentation_profile_asset, presentation_profile, " +
                        "publication_member, publication_revision, publication, post_availability, post_snapshot, " +
                        "post_source_binding, post cascade",
                )
            }
        }
    }

    @Test
    fun `round trips a singleton configuration with normalized metadata and profile assets`() =
        inTransaction {
            val publicationId = PublicationId(UUID.randomUUID())
            val profile = profile(version = 4)
            val configuration = configuration(publicationId, profile.reference())
            val createdAt = Instant.parse("2026-08-25T01:02:03Z")
            val synchronizedAt = Instant.parse("2026-08-25T01:03:04Z")
            insertPublication(publicationId)

            repository.saveProfile(profile, createdAt)
            repository.save(configuration, synchronizedAt)

            assertThat(repository.findCurrent()).isEqualTo(configuration)
            assertThat(repository.findProfile(profile.reference())).isEqualTo(profile)
            val mapper = JsonMapper.builder().build()
            val tokenJson = PresentationProfileTable.selectAll().single()[PresentationProfileTable.tokenJson]
            assertThat(mapper.readTree(tokenJson)).isEqualTo(
                mapper.readTree("""{"colorMode":"DARK","contentWidth":"WIDE","density":"COMPACT"}"""),
            )
            assertThat(tokenJson).doesNotContain("xyz.", "@class", "css", "javascript", "head")
            val metadataJson = SiteConfigurationTable.selectAll().single()[SiteConfigurationTable.metadataJson]
            assertThat(mapper.readTree(metadataJson)).isEqualTo(
                mapper.readTree(
                    """
                    {"siteName":"Example Blog","defaultDescription":"A normal description","languageTag":"ko-KR",
                      "favicon":{"key":"favicon","version":1,"integrity":"sha256-favicon"}}
                    """.trimIndent(),
                ),
            )
            assertThat(metadataJson).doesNotContain("xyz.", "@class", "css", "javascript", "head")
            assertThat(
                PresentationProfileTable.selectAll().single()[PresentationProfileTable.createdAt],
            ).isEqualTo(createdAt.atOffset(java.time.ZoneOffset.UTC))
            assertThat(
                SiteConfigurationTable.selectAll().single()[SiteConfigurationTable.syncedAt],
            ).isEqualTo(synchronizedAt.atOffset(java.time.ZoneOffset.UTC))
        }

    @Test
    fun `keeps profile versions immutable and resolves only an explicitly activated current version`() =
        inTransaction {
            val profileId = PresentationProfileId(UUID.randomUUID())
            val key = PresentationProfileKey("notion-like")
            val first = profile(profileId, key, 1)
            val second = profile(profileId, key, 2)

            repository.saveProfile(first, Instant.parse("2026-08-25T01:02:03Z"))
            repository.saveProfile(second, Instant.parse("2026-08-25T01:02:04Z"))

            assertThat(repository.findCurrentProfile(key)).isNull()
            repository.activateProfile(first.reference())
            assertThat(repository.findCurrentProfile(key)).isEqualTo(first)
            repository.activateProfile(second.reference())
            assertThat(repository.findCurrentProfile(key)).isEqualTo(second)
            assertThat(repository.findProfile(first.reference())).isEqualTo(first)
        }

    @Test
    fun `upserts the normalized site singleton instead of inserting another site row`() =
        inTransaction {
            val publicationId = PublicationId(UUID.randomUUID())
            val profile = profile(version = 1)
            val replacementProfile = profile(profile.id, profile.key, 2)
            insertPublication(publicationId)
            repository.saveProfile(profile, Instant.parse("2026-08-25T01:02:03Z"))
            repository.saveProfile(replacementProfile, Instant.parse("2026-08-25T01:02:04Z"))

            repository.save(configuration(publicationId, profile.reference()), Instant.parse("2026-08-25T01:03:03Z"))
            val replacement =
                configuration(publicationId, replacementProfile.reference()).copy(
                    rootDocument = SourceDocumentRef(SourceId("notion-main"), "replacement-root"),
                )
            repository.save(replacement, Instant.parse("2026-08-25T01:04:03Z"))

            assertThat(SiteConfigurationTable.selectAll().count()).isEqualTo(1)
            assertThat(SiteConfigurationTable.selectAll().single()[SiteConfigurationTable.siteId]).isEqualTo(1.toShort())
            assertThat(repository.findCurrent()).isEqualTo(replacement)
        }

    @Test
    fun `uses the caller transaction so a failed profile save rolls back`() {
        val profile = profile(version = 1)

        assertThatThrownBy {
            transaction(database) {
                repository.saveProfile(profile, Instant.parse("2026-08-25T01:02:03Z"))
                error("caller failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        inTransaction {
            assertThat(repository.findProfile(profile.reference())).isNull()
        }
    }

    private fun profile(
        id: PresentationProfileId = PresentationProfileId(UUID.randomUUID()),
        key: PresentationProfileKey = PresentationProfileKey("notion-like"),
        version: Long,
    ): PresentationProfile =
        PresentationProfile(
            id = id,
            key = key,
            version = version,
            tokens = PresentationTokens(PresentationColorMode.DARK, PresentationContentWidth.WIDE, PresentationDensity.COMPACT),
            styleSheets =
                listOf(
                    PresentationAssetRef("base", 1, "sha256-base"),
                    PresentationAssetRef("theme", 3, "sha256-theme"),
                ),
            scripts =
                listOf(
                    PresentationAssetRef("navigation", 2, "sha256-navigation"),
                    PresentationAssetRef("analytics", 5, "sha256-analytics"),
                ),
        )

    private fun configuration(
        publicationId: PublicationId,
        profile: PresentationProfileRef,
    ): SiteConfiguration =
        SiteConfiguration(
            publicationId = publicationId,
            rootDocument = SourceDocumentRef(SourceId("notion-main"), "root"),
            headerDocument = SourceDocumentRef(SourceId("notion-main"), "header"),
            footerDocument = SourceDocumentRef(SourceId("notion-main"), "footer"),
            metadata =
                SiteMetadata(
                    siteName = "Example Blog",
                    defaultDescription = "A normal description",
                    languageTag = "ko-KR",
                    favicon = PresentationAssetRef("favicon", 1, "sha256-favicon"),
                ),
            presentationProfile = profile,
        )

    private fun PresentationProfile.reference() = PresentationProfileRef(id, version)

    private fun insertPublication(publicationId: PublicationId) {
        PublicationTable.insert {
            it[PublicationTable.publicationId] = publicationId.value
        }
    }

    private fun inTransaction(block: () -> Unit) {
        transaction(database) { block() }
    }

    private fun connection(block: (Connection) -> Unit) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)
    }
}
