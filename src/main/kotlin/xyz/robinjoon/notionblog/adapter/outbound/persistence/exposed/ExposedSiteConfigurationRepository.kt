package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PresentationProfileAssetTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PresentationProfileTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.SiteConfigurationTable
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
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
import java.time.Instant
import java.time.ZoneOffset

class ExposedSiteConfigurationRepository : SiteConfigurationRepository {
    private val jsonMapper = JsonMapper.builder().build()

    override fun findCurrent(): SiteConfiguration? =
        SiteConfigurationTable
            .selectAll()
            .singleOrNull()
            ?.toSiteConfiguration()

    override fun save(
        configuration: SiteConfiguration,
        synchronizedAt: Instant,
    ) {
        SiteConfigurationTable.upsert(SiteConfigurationTable.siteId) {
            it[siteId] = SITE_ID
            it[publicationId] = configuration.publicationId.value
            it[rootSourceId] = configuration.rootDocument.sourceId.value
            it[rootExternalId] = configuration.rootDocument.externalId
            it[headerSourceId] = configuration.headerDocument?.sourceId?.value
            it[headerExternalId] = configuration.headerDocument?.externalId
            it[footerSourceId] = configuration.footerDocument?.sourceId?.value
            it[footerExternalId] = configuration.footerDocument?.externalId
            it[metadataJson] = encodeMetadata(configuration.metadata)
            it[presentationProfileId] = configuration.presentationProfile.id.value
            it[presentationProfileVersion] = configuration.presentationProfile.version
            it[syncedAt] = synchronizedAt.asOffsetDateTime()
        }
    }

    override fun findProfile(reference: PresentationProfileRef): PresentationProfile? =
        PresentationProfileTable
            .selectAll()
            .where {
                (PresentationProfileTable.presentationProfileId eq reference.id.value) and
                    (PresentationProfileTable.version eq reference.version)
            }.singleOrNull()
            ?.toPresentationProfile()

    override fun findCurrentProfile(key: PresentationProfileKey): PresentationProfile? =
        PresentationProfileTable
            .selectAll()
            .where {
                (PresentationProfileTable.profileKey eq key.value) and
                    (PresentationProfileTable.isCurrent eq true)
            }.singleOrNull()
            ?.toPresentationProfile()

    override fun saveProfile(
        profile: PresentationProfile,
        createdAt: Instant,
    ) {
        PresentationProfileTable.insert {
            it[presentationProfileId] = profile.id.value
            it[profileKey] = profile.key.value
            it[version] = profile.version
            it[tokenJson] = encodeTokens(profile.tokens)
            it[isCurrent] = false
            it[PresentationProfileTable.createdAt] = createdAt.asOffsetDateTime()
        }
        saveAssets(profile, AssetKind.STYLE_SHEET, profile.styleSheets)
        saveAssets(profile, AssetKind.SCRIPT, profile.scripts)
    }

    override fun activateProfile(reference: PresentationProfileRef) {
        val row =
            PresentationProfileTable
                .selectAll()
                .where {
                    (PresentationProfileTable.presentationProfileId eq reference.id.value) and
                        (PresentationProfileTable.version eq reference.version)
                }.singleOrNull()
                ?: error("presentation profile ${reference.id.value} version ${reference.version} does not exist")

        PresentationProfileTable.update({ PresentationProfileTable.profileKey eq row[PresentationProfileTable.profileKey] }) {
            it[isCurrent] = false
        }
        check(
            PresentationProfileTable.update({
                (PresentationProfileTable.presentationProfileId eq reference.id.value) and
                    (PresentationProfileTable.version eq reference.version)
            }) {
                it[isCurrent] = true
            } == 1,
        ) { "presentation profile ${reference.id.value} version ${reference.version} was not activated" }
    }

    private fun saveAssets(
        profile: PresentationProfile,
        kind: AssetKind,
        assets: List<PresentationAssetRef>,
    ) {
        assets.forEachIndexed { position, asset ->
            PresentationProfileAssetTable.insert {
                it[presentationProfileId] = profile.id.value
                it[presentationProfileVersion] = profile.version
                it[assetKind] = kind.name
                it[assetKey] = asset.key
                it[assetVersion] = asset.version
                it[integrity] = asset.integrity
                it[PresentationProfileAssetTable.position] = position
            }
        }
    }

    private fun ResultRow.toSiteConfiguration(): SiteConfiguration =
        SiteConfiguration(
            publicationId = PublicationId(this[SiteConfigurationTable.publicationId]),
            rootDocument =
                SourceDocumentRef(
                    SourceId(this[SiteConfigurationTable.rootSourceId]),
                    this[SiteConfigurationTable.rootExternalId],
                ),
            headerDocument =
                sourceDocumentOrNull(
                    this[SiteConfigurationTable.headerSourceId],
                    this[SiteConfigurationTable.headerExternalId],
                ),
            footerDocument =
                sourceDocumentOrNull(
                    this[SiteConfigurationTable.footerSourceId],
                    this[SiteConfigurationTable.footerExternalId],
                ),
            metadata = decodeMetadata(this[SiteConfigurationTable.metadataJson]),
            presentationProfile =
                PresentationProfileRef(
                    PresentationProfileId(this[SiteConfigurationTable.presentationProfileId]),
                    this[SiteConfigurationTable.presentationProfileVersion],
                ),
        )

    private fun ResultRow.toPresentationProfile(): PresentationProfile {
        val id = PresentationProfileId(this[PresentationProfileTable.presentationProfileId])
        val version = this[PresentationProfileTable.version]
        return PresentationProfile(
            id = id,
            key = PresentationProfileKey(this[PresentationProfileTable.profileKey]),
            version = version,
            tokens = decodeTokens(this[PresentationProfileTable.tokenJson]),
            styleSheets = findAssets(id, version, AssetKind.STYLE_SHEET),
            scripts = findAssets(id, version, AssetKind.SCRIPT),
        )
    }

    private fun findAssets(
        id: PresentationProfileId,
        version: Long,
        kind: AssetKind,
    ): List<PresentationAssetRef> =
        PresentationProfileAssetTable
            .selectAll()
            .where {
                (PresentationProfileAssetTable.presentationProfileId eq id.value) and
                    (PresentationProfileAssetTable.presentationProfileVersion eq version) and
                    (PresentationProfileAssetTable.assetKind eq kind.name)
            }.orderBy(PresentationProfileAssetTable.position to SortOrder.ASC)
            .map { row ->
                PresentationAssetRef(
                    row[PresentationProfileAssetTable.assetKey],
                    row[PresentationProfileAssetTable.assetVersion],
                    row[PresentationProfileAssetTable.integrity],
                )
            }

    private fun encodeMetadata(metadata: SiteMetadata): String =
        jsonMapper
            .createObjectNode()
            .apply {
                put("siteName", metadata.siteName)
                if (metadata.defaultDescription ==
                    null
                ) {
                    putNull("defaultDescription")
                } else {
                    put("defaultDescription", metadata.defaultDescription)
                }
                put("languageTag", metadata.languageTag)
                set("favicon", metadata.favicon?.toJson() ?: jsonMapper.nullNode())
            }.toString()

    private fun decodeMetadata(value: String): SiteMetadata {
        val node = jsonMapper.readTree(value)
        return SiteMetadata(
            siteName = node.requiredText("siteName"),
            defaultDescription = node.optionalText("defaultDescription"),
            languageTag = node.requiredText("languageTag"),
            favicon = node.optionalAsset("favicon"),
        )
    }

    private fun encodeTokens(tokens: PresentationTokens): String =
        jsonMapper
            .createObjectNode()
            .apply {
                put("colorMode", tokens.colorMode.name)
                put("contentWidth", tokens.contentWidth.name)
                put("density", tokens.density.name)
            }.toString()

    private fun decodeTokens(value: String): PresentationTokens {
        val node = jsonMapper.readTree(value)
        return PresentationTokens(
            colorMode = PresentationColorMode.valueOf(node.requiredText("colorMode")),
            contentWidth = PresentationContentWidth.valueOf(node.requiredText("contentWidth")),
            density = PresentationDensity.valueOf(node.requiredText("density")),
        )
    }

    private fun PresentationAssetRef.toJson(): JsonNode =
        jsonMapper.createObjectNode().apply {
            put("key", key)
            put("version", version)
            put("integrity", integrity)
        }

    private fun JsonNode.optionalAsset(field: String): PresentationAssetRef? {
        val asset = get(field) ?: return null
        if (asset.isNull) return null
        return PresentationAssetRef(asset.requiredText("key"), asset.requiredLong("version"), asset.requiredText("integrity"))
    }

    private fun JsonNode.requiredText(field: String): String =
        get(field)
            ?.takeUnless(JsonNode::isNull)
            ?.asString()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("missing or blank JSON field: $field")

    private fun JsonNode.optionalText(field: String): String? =
        get(field)
            ?.takeUnless(JsonNode::isNull)
            ?.asString()

    private fun JsonNode.requiredLong(field: String): Long =
        get(field)
            ?.takeUnless(JsonNode::isNull)
            ?.takeIf(JsonNode::canConvertToLong)
            ?.longValue()
            ?: throw IllegalArgumentException("missing or invalid JSON field: $field")

    private fun sourceDocumentOrNull(
        sourceId: String?,
        externalId: String?,
    ): SourceDocumentRef? =
        when {
            sourceId == null && externalId == null -> null
            sourceId != null && externalId != null -> SourceDocumentRef(SourceId(sourceId), externalId)
            else -> throw IllegalArgumentException("source reference columns must be both null or both present")
        }

    private fun Instant.asOffsetDateTime() = atOffset(ZoneOffset.UTC)

    private enum class AssetKind {
        STYLE_SHEET,
        SCRIPT,
    }

    private companion object {
        const val SITE_ID: Short = 1
    }
}
