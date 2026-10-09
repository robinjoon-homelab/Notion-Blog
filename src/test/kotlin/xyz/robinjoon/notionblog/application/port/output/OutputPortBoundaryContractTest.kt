package xyz.robinjoon.notionblog.application.port.output

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.application.model.ResolvedPresentationAsset
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.application.port.output.source.PostSource
import xyz.robinjoon.notionblog.application.port.output.source.SiteConfigurationSource
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import java.time.Instant

class OutputPortBoundaryContractTest {
    @Test
    fun `ports do not expose implementation boundary types`() {
        val portTypes =
            listOf(
                PostSource::class.java,
                SiteConfigurationSource::class.java,
                PostRepository::class.java,
                PublicationRepository::class.java,
                SiteConfigurationRepository::class.java,
                SyncStateRepository::class.java,
                PresentationAssetCatalog::class.java,
            )

        val exposedTypes =
            portTypes.flatMap { type ->
                type.methods.flatMap { method ->
                    listOf(method.returnType) + method.parameterTypes.toList() + method.exceptionTypes.toList()
                }
            }

        assertThat(exposedTypes.map { it.name }).doesNotContain("org.jetbrains.exposed.sql.Table", "org.jetbrains.exposed.sql.ResultRow")
        assertThat(exposedTypes.map { it.simpleName }).noneMatch { name ->
            name.startsWith("Notion") || name == "Table" || name == "ResultRow"
        }
    }

    @Test
    fun `repository ports expose named batch operations and no default methods`() {
        val repositories =
            listOf(
                PostRepository::class.java,
                PublicationRepository::class.java,
                SiteConfigurationRepository::class.java,
                SyncStateRepository::class.java,
            )

        assertThat(PostRepository::class.java.methods.map { it.name })
            .contains("findBindingsBySourceDocuments", "findBindingsByPostIds", "findAvailabilities", "saveAvailabilities")
        assertThat(PublicationRepository::class.java.methods.map { it.name })
            .anyMatch { it.startsWith("createRevision") }
        assertThat(PublicationRepository::class.java.methods.map { it.name })
            .anyMatch { it.startsWith("updateRevision") }
        assertThat(PublicationRepository::class.java.methods.map { it.name })
            .anyMatch { it.startsWith("saveMembers") }
        assertThat(PublicationRepository::class.java.methods.map { it.name })
            .anyMatch { it.startsWith("findMembers") }
        assertThat(PublicationRepository::class.java.methods.map { it.name })
            .anyMatch { it.startsWith("findActiveDirectChildren") }
        assertThat(PublicationRepository::class.java.methods.map { it.name })
            .anyMatch { it.startsWith("findStagingRevisions") }
        assertThat(PublicationRepository::class.java.methods.map { it.name })
            .noneMatch { it.startsWith("activate") || it.startsWith("saveStagingRevision") }
        assertThat(PostRepository::class.java.methods.map { it.name })
            .doesNotContain("findById", "findBySourceDocument", "save")
        assertThat(SyncStateRepository::class.java.methods.map { it.name }).contains("find")
        assertThat(SiteConfigurationRepository::class.java.methods.map { it.name })
            .contains("saveProfile", "activateProfile")
        val activateProfile = SiteConfigurationRepository::class.java.methods.single { it.name == "activateProfile" }
        assertThat(activateProfile.parameterTypes).containsExactly(PresentationProfileRef::class.java)
        val saveConfiguration = SiteConfigurationRepository::class.java.methods.single { it.name == "save" }
        assertThat(saveConfiguration.parameterTypes).containsExactly(SiteConfiguration::class.java, Instant::class.java)
        val saveProfile = SiteConfigurationRepository::class.java.methods.single { it.name == "saveProfile" }
        assertThat(saveProfile.parameterTypes).containsExactly(PresentationProfile::class.java, Instant::class.java)
        assertThat(repositories.flatMap { it.methods.asList() }.none { it.isDefault }).isTrue()
    }

    @Test
    fun `snapshot content failure is a source neutral persistence boundary exception`() {
        val exception = SnapshotContentException("snapshot cannot be decoded")

        assertThat(exception).isInstanceOf(RuntimeException::class.java)
        assertThat(SnapshotContentException::class.java.packageName)
            .isEqualTo("xyz.robinjoon.notionblog.application.port.output.persistence")
        assertThat(exception.message).doesNotContain("Notion", "Table", "ResultRow", "xyz.", "java.", "kotlin.")
    }

    @Test
    fun `catalog resolves an explicitly current asset reference for an external key`() {
        val reference = PresentationAssetRef("favicon", 3, "sha384-favicon")
        val descriptor = PresentationAssetDescriptor("/assets/favicon.ico", "image/x-icon", "sha384-favicon")
        val resolved = ResolvedPresentationAsset(reference, descriptor)

        assertThat(resolved.reference).isEqualTo(reference)
        assertThat(resolved.descriptor).isEqualTo(descriptor)
        assertThat(PresentationAssetCatalog::class.java.methods.map { it.name })
            .anyMatch { it.startsWith("resolveCurrent") }
    }
}
