package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SiteConfigurationTable
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
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
            it[rootSourceId] =
                configuration.documents.root
                    ?.sourceId
                    ?.value
            it[rootExternalId] = configuration.documents.root?.externalId
            it[headerSourceId] =
                configuration.documents.header
                    ?.sourceId
                    ?.value
            it[headerExternalId] = configuration.documents.header?.externalId
            it[footerSourceId] =
                configuration.documents.footer
                    ?.sourceId
                    ?.value
            it[footerExternalId] = configuration.documents.footer?.externalId
            it[metadataJson] = encodeMetadata(configuration.metadata)
            it[presentationProfileId] = configuration.presentationProfile.id.value
            it[presentationProfileVersion] = configuration.presentationProfile.version
            it[syncedAt] = synchronizedAt.asOffsetDateTime()
        }
    }

    private fun ResultRow.toSiteConfiguration(): SiteConfiguration =
        SiteConfiguration.restore(
            publicationId = PublicationId(this[SiteConfigurationTable.publicationId]),
            documents = toSiteDocuments(),
            metadata = decodeMetadata(this[SiteConfigurationTable.metadataJson]),
            presentationProfile =
                PresentationProfileRef(
                    PresentationProfileId(this[SiteConfigurationTable.presentationProfileId]),
                    this[SiteConfigurationTable.presentationProfileVersion],
                ),
        )

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

    private fun ResultRow.toSiteDocuments(): SiteDocuments =
        SiteDocuments(
            root = sourceDocumentOrNull(this[SiteConfigurationTable.rootSourceId], this[SiteConfigurationTable.rootExternalId]),
            header = sourceDocumentOrNull(this[SiteConfigurationTable.headerSourceId], this[SiteConfigurationTable.headerExternalId]),
            footer = sourceDocumentOrNull(this[SiteConfigurationTable.footerSourceId], this[SiteConfigurationTable.footerExternalId]),
        )

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

    private companion object {
        const val SITE_ID: Short = 1
    }
}
