package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionSettingsRowResponse
import xyz.robinjoon.notionblog.application.model.ImportedSiteConfiguration
import xyz.robinjoon.notionblog.application.model.ImportedSiteMetadata
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal class NotionSettingsMapper(
    private val sourceId: SourceId,
) {
    private val logger = LoggerFactory.getLogger(NotionSettingsMapper::class.java)

    fun map(rows: List<NotionSettingsRowResponse>): ImportedSiteConfiguration =
        try {
            val enabledRows =
                rows
                    .asSequence()
                    .filter(::isEnabled)
                    .map(::parseEnabledRow)
                    .toList()

            val root =
                enabledRows.firstOrNull { it.key == ROOT_PAGE_KEY }
                    ?: throw InvalidSettingsException()
            requireKind(root, PAGE_KIND)

            val header = enabledRows.firstOrNull { it.key == HEADER_KEY }?.also { requireKind(it, BLOCKS_KIND) }
            val footer = enabledRows.firstOrNull { it.key == FOOTER_KEY }?.also { requireKind(it, BLOCKS_KIND) }
            val head = enabledRows.firstOrNull { it.key == HEAD_KEY }?.also { requireKind(it, HEAD_KIND) }

            ImportedSiteConfiguration(
                rootDocument = sourceDocument(root.page),
                headerDocument = header?.let { sourceDocument(it.page) },
                footerDocument = footer?.let { sourceDocument(it.page) },
                metadata =
                    head?.let { metadata(it.data) }
                        ?: ImportedSiteMetadata(DEFAULT_SITE_NAME, null, DEFAULT_LANGUAGE_TAG, null),
                presentationProfileKey = head?.let { presentationProfileKey(it.data) },
            )
        } catch (exception: InvalidSettingsException) {
            throw configurationFailure(exception)
        } catch (exception: IllegalArgumentException) {
            throw configurationFailure(exception)
        }

    private fun isEnabled(row: NotionSettingsRowResponse): Boolean =
        row.properties
            .property(ENABLED_PROPERTY)
            ?.takeIf { it.typeName() == CHECKBOX_TYPE }
            ?.get(CHECKBOX_TYPE)
            ?.takeIf(JsonNode::isBoolean)
            ?.asBoolean()
            ?: false

    private fun parseEnabledRow(row: NotionSettingsRowResponse): ParsedRow {
        val kind = row.properties.requiredSelectName(KIND_PROPERTY).lowercase(Locale.ROOT)
        return ParsedRow(
            key = row.properties.requiredPlainText(KEY_PROPERTY),
            kind = kind,
            page = if (kind in pageReferenceKinds) row.properties.optionalRichText(PAGE_PROPERTY) else null,
            data = if (kind == HEAD_KIND) row.properties.optionalRichText(DATA_PROPERTY) else null,
        )
    }

    private fun requireKind(
        row: ParsedRow,
        expected: String,
    ) {
        if (row.kind != expected) throw InvalidSettingsException()
    }

    private fun sourceDocument(reference: String?): SourceDocumentRef {
        val externalId = reference?.let(::parsePageId) ?: throw InvalidSettingsException()
        return SourceDocumentRef(sourceId, externalId)
    }

    private fun metadata(data: String?): ImportedSiteMetadata {
        val values = dataObject(data)
        val siteName = values.optionalText(SITE_NAME_FIELD) ?: DEFAULT_SITE_NAME
        val defaultDescription = values.optionalText(DEFAULT_DESCRIPTION_FIELD)
        val languageTag = values.optionalText(LANGUAGE_TAG_FIELD) ?: DEFAULT_LANGUAGE_TAG
        val faviconAssetKey = values.optionalText(FAVICON_ASSET_KEY_FIELD)
        if (
            siteName.isBlank() ||
            defaultDescription?.isBlank() == true ||
            languageTag.isBlank() ||
            faviconAssetKey?.isBlank() == true
        ) {
            throw InvalidSettingsException()
        }
        return ImportedSiteMetadata(siteName, defaultDescription, languageTag, faviconAssetKey)
    }

    private fun presentationProfileKey(data: String?): PresentationProfileKey? {
        val value = dataObject(data).optionalText(PRESENTATION_PROFILE_KEY_FIELD) ?: return null
        if (value.isBlank()) throw InvalidSettingsException()
        return PresentationProfileKey(value)
    }

    private fun dataObject(data: String?): JsonNode {
        if (data == null) return objectMapper.createObjectNode()
        val parsed =
            try {
                objectMapper.readTree(data)
            } catch (exception: JacksonException) {
                throw InvalidSettingsException(exception)
            }
        if (parsed == null || !parsed.isObject) throw InvalidSettingsException()
        val fields = parsed.properties().map { it.key }.toSet()
        if (!allowedHeadFields.containsAll(fields)) throw InvalidSettingsException()
        return parsed
    }

    private fun parsePageId(value: String): String? {
        val trimmed = value.trim()
        normalizePageId(trimmed)?.let { return it }

        val uri =
            try {
                URI(trimmed)
            } catch (exception: URISyntaxException) {
                logger.debug("Notion page reference syntax is invalid: {}", exception.javaClass.simpleName)
                return null
            }
        if (!isNotionHost(uri.host)) return null
        return pageIdPattern
            .findAll(uri.path.orEmpty())
            .lastOrNull()
            ?.value
            ?.let(::normalizePageId)
    }

    private fun normalizePageId(value: String): String? = NotionIdNormalizer.normalizeOrNull(value)

    private fun isNotionHost(host: String?): Boolean {
        val normalized = host?.lowercase(Locale.ROOT) ?: return false
        return normalized == "notion.com" || normalized.endsWith(".notion.com") ||
            normalized == "notion.so" || normalized.endsWith(".notion.so") ||
            normalized == "notion.site" || normalized.endsWith(".notion.site")
    }

    private fun JsonNode.property(name: String): JsonNode? = get(name)?.takeIf(JsonNode::isObject)

    private fun JsonNode.typeName(): String? = get("type")?.takeIf(JsonNode::isString)?.stringValue()

    private fun JsonNode.requiredPlainText(name: String): String =
        property(name)
            ?.takeIf { it.typeName() in setOf(TITLE_TYPE, RICH_TEXT_TYPE) }
            ?.plainText()
            ?.takeIf(String::isNotBlank)
            ?: throw InvalidSettingsException()

    private fun JsonNode.requiredSelectName(name: String): String =
        property(name)
            ?.takeIf { it.typeName() == SELECT_TYPE }
            ?.get(SELECT_TYPE)
            ?.takeIf(JsonNode::isObject)
            ?.get("name")
            ?.takeIf(JsonNode::isString)
            ?.stringValue()
            ?.takeIf(String::isNotBlank)
            ?: throw InvalidSettingsException()

    private fun JsonNode.optionalRichText(name: String): String? {
        val property = property(name) ?: return null
        if (property.typeName() != RICH_TEXT_TYPE) throw InvalidSettingsException()
        return property.plainText()
    }

    private fun JsonNode.plainText(): String {
        val values =
            get(TITLE_TYPE)?.takeIf(JsonNode::isArray) ?: get(RICH_TEXT_TYPE)?.takeIf(JsonNode::isArray)
                ?: throw InvalidSettingsException()
        return values.toList().joinToString(separator = "") { entry ->
            entry
                .get("plain_text")
                ?.takeIf(JsonNode::isString)
                ?.stringValue()
                ?: throw InvalidSettingsException()
        }
    }

    private fun JsonNode.optionalText(name: String): String? {
        val value = get(name) ?: return null
        return value.takeIf(JsonNode::isString)?.stringValue() ?: throw InvalidSettingsException()
    }

    private fun configurationFailure(cause: Throwable): SourceConfigurationException =
        SourceConfigurationException("Notion site configuration is invalid", cause)

    private data class ParsedRow(
        val key: String,
        val kind: String,
        val page: String?,
        val data: String?,
    )

    private class InvalidSettingsException(
        cause: Throwable? = null,
    ) : RuntimeException(cause)

    private companion object {
        val objectMapper = JsonMapper.builder().build()
        val pageIdPattern =
            Regex(
                "(?<![0-9a-f])([0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})(?![0-9a-f])",
                RegexOption.IGNORE_CASE,
            )
        val allowedHeadFields =
            setOf(
                "siteName",
                "defaultDescription",
                "languageTag",
                "faviconAssetKey",
                "presentationProfileKey",
            )
        val pageReferenceKinds = setOf(PAGE_KIND, BLOCKS_KIND)
        const val ROOT_PAGE_KEY = "rootPage"
        const val HEADER_KEY = "header"
        const val FOOTER_KEY = "footer"
        const val HEAD_KEY = "head"
        const val PAGE_KIND = "page"
        const val BLOCKS_KIND = "blocks"
        const val HEAD_KIND = "head"
        const val KEY_PROPERTY = "Key"
        const val KIND_PROPERTY = "Kind"
        const val ENABLED_PROPERTY = "Enabled"
        const val PAGE_PROPERTY = "Page"
        const val DATA_PROPERTY = "Data"
        const val TITLE_TYPE = "title"
        const val RICH_TEXT_TYPE = "rich_text"
        const val SELECT_TYPE = "select"
        const val CHECKBOX_TYPE = "checkbox"
        const val SITE_NAME_FIELD = "siteName"
        const val DEFAULT_DESCRIPTION_FIELD = "defaultDescription"
        const val LANGUAGE_TAG_FIELD = "languageTag"
        const val FAVICON_ASSET_KEY_FIELD = "faviconAssetKey"
        const val PRESENTATION_PROFILE_KEY_FIELD = "presentationProfileKey"
        const val DEFAULT_SITE_NAME = "Blog"
        const val DEFAULT_LANGUAGE_TAG = "en"
    }
}
