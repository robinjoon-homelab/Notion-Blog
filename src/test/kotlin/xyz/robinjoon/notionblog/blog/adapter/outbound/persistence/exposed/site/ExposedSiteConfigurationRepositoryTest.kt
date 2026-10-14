package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.site

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedSiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SiteConfigurationTable
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExposedSiteConfigurationRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedSiteConfigurationRepository()
    private val profile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 4)

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `round trips a singleton configuration with normalized metadata`() =
        inTransaction {
            val publicationId = PublicationId(UUID.randomUUID())
            val configuration = configuration(publicationId, profile)
            val synchronizedAt = Instant.parse("2026-08-25T01:03:04Z")
            insertPublication(publicationId)

            repository.save(configuration, synchronizedAt)

            assertThat(repository.findCurrent()).isEqualTo(configuration)
            val mapper = JsonMapper.builder().build()
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
                SiteConfigurationTable.selectAll().single()[SiteConfigurationTable.syncedAt],
            ).isEqualTo(synchronizedAt.atOffset(ZoneOffset.UTC))
        }

    @Test
    fun `upserts the normalized site singleton instead of inserting another site row`() =
        inTransaction {
            val publicationId = PublicationId(UUID.randomUUID())
            insertPublication(publicationId)

            repository.save(configuration(publicationId, profile), Instant.parse("2026-08-25T01:03:03Z"))
            val base = configuration(publicationId, PresentationProfileRef(profile.id, 5))
            val replacement =
                SiteConfiguration.restore(
                    base.publicationId,
                    base.documents.copy(root = SourceDocumentRef(SourceId("notion-main"), "replacement-root")),
                    base.metadata,
                    base.presentationProfile,
                )
            repository.save(replacement, Instant.parse("2026-08-25T01:04:03Z"))

            assertThat(SiteConfigurationTable.selectAll().count()).isEqualTo(1)
            assertThat(SiteConfigurationTable.selectAll().single()[SiteConfigurationTable.siteId]).isEqualTo(1.toShort())
            assertThat(repository.findCurrent()).isEqualTo(replacement)
        }

    private fun configuration(
        publicationId: PublicationId,
        profile: PresentationProfileRef,
    ): SiteConfiguration =
        SiteConfiguration.restore(
            publicationId = publicationId,
            documents =
                SiteDocuments(
                    root = SourceDocumentRef(SourceId("notion-main"), "root"),
                    header = SourceDocumentRef(SourceId("notion-main"), "header"),
                    footer = SourceDocumentRef(SourceId("notion-main"), "footer"),
                ),
            metadata =
                SiteMetadata(
                    siteName = "Example Blog",
                    defaultDescription = "A normal description",
                    languageTag = "ko-KR",
                    favicon = PresentationAssetRef("favicon", 1, "sha256-favicon"),
                ),
            presentationProfile = profile,
        )

    // The site row references its publication, so the publication row is a fixture rather than another adapter call.
    private fun insertPublication(publicationId: PublicationId) {
        PublicationTable.insert {
            it[PublicationTable.publicationId] = publicationId.value
        }
    }

    private fun inTransaction(block: () -> Unit) {
        database.inTransaction(block)
    }
}
