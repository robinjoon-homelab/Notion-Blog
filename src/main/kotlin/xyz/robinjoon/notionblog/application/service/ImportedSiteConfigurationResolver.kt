package xyz.robinjoon.notionblog.application.service

import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import java.util.IllformedLocaleException
import java.util.Locale

class ImportedSiteConfigurationResolver(
    private val siteConfigurationRepository: SiteConfigurationRepository,
    private val presentationAssetCatalog: PresentationAssetCatalog,
    private val defaultPresentationProfileKey: PresentationProfileKey,
    private val publicationIdFactory: () -> PublicationId,
) {
    fun resolve(
        imported: ImportedSiteConfiguration,
        current: SiteConfiguration?,
    ): SiteConfiguration {
        val profile = resolveProfile(imported)
        val favicon = resolveFavicon(imported)
        val metadata =
            SiteMetadata(
                siteName = imported.metadata.siteName,
                defaultDescription = imported.metadata.defaultDescription,
                languageTag = validatedLanguageTag(imported.metadata.languageTag),
                favicon = favicon,
            )
        return SiteConfiguration(
            publicationId = current?.publicationId ?: publicationIdFactory(),
            rootDocument = imported.rootDocument,
            headerDocument = imported.headerDocument,
            footerDocument = imported.footerDocument,
            metadata = metadata,
            presentationProfile = profile.reference(),
        )
    }

    private fun resolveProfile(imported: ImportedSiteConfiguration): PresentationProfile {
        val key = imported.presentationProfileKey ?: defaultPresentationProfileKey
        val profile =
            requireNotNull(siteConfigurationRepository.findCurrentProfile(key)) {
                "presentation profile must be a registered current profile: ${key.value}"
            }
        require(profile.key == key) { "resolved presentation profile key must match the requested key" }
        profile.styleSheets.forEach(::requireRegisteredAsset)
        profile.scripts.forEach(::requireRegisteredAsset)
        return profile
    }

    private fun resolveFavicon(imported: ImportedSiteConfiguration): PresentationAssetRef? =
        imported.metadata.faviconAssetKey?.let { key ->
            val resolved =
                requireNotNull(presentationAssetCatalog.resolveCurrent(key)) {
                    "favicon must resolve to a registered current presentation asset: $key"
                }
            require(resolved.reference.key == key) {
                "resolved favicon key must match the requested key"
            }
            require(resolved.descriptor.integrity == resolved.reference.integrity) {
                "favicon descriptor integrity must match its reference"
            }
            resolved.reference
        }

    private fun requireRegisteredAsset(reference: PresentationAssetRef) {
        val descriptor =
            requireNotNull(presentationAssetCatalog.resolve(reference)) {
                "presentation profile asset must be registered exactly: ${reference.key}@${reference.version}"
            }
        require(descriptor.integrity == reference.integrity) {
            "presentation asset descriptor integrity must match its reference"
        }
    }

    private fun validatedLanguageTag(languageTag: String): String =
        try {
            val normalized =
                Locale
                    .Builder()
                    .setLanguageTag(languageTag)
                    .build()
                    .toLanguageTag()
            require(normalized.equals(languageTag, ignoreCase = true)) { "language tag must be a valid BCP 47 tag" }
            normalized
        } catch (exception: IllformedLocaleException) {
            throw IllegalArgumentException("language tag must be a valid BCP 47 tag", exception)
        }

    private fun PresentationProfile.reference(): PresentationProfileRef = PresentationProfileRef(id, version)
}
