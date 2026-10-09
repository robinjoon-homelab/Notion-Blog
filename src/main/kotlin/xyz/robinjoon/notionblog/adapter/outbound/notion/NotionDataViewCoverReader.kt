package xyz.robinjoon.notionblog.adapter.outbound.notion

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionBlockMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionBlockMappingException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import java.net.URI
import java.util.Locale

internal class NotionDataViewCoverReader(
    private val client: NotionApiClient,
    private val blockMapper: NotionBlockMapper,
) {
    fun read(
        page: NotionPageResponse,
        cover: NotionGalleryCover?,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): MediaSource? {
        checkDeadline()
        val source =
            when (cover) {
                NotionGalleryCover.PageCover -> page.cover?.takeUnless(JsonNode::isNull)?.let(::media)
                NotionGalleryCover.PageContent -> contentCover(page.id, checkDeadline, reserve)
                is NotionGalleryCover.Property -> propertyCover(page, cover.propertyId, checkDeadline, reserve)
                null -> null
            }
        checkDeadline()
        return source
    }

    fun icon(page: NotionPageResponse): BlockIcon? {
        page.icon?.let(::validateExpiry)
        val icon = blockMapper.mapIcon(page.icon)
        when (icon) {
            is BlockIcon.Media -> validateSource(icon.source)
            is BlockIcon.CustomEmoji -> validateSource(icon.source)
            else -> Unit
        }
        return icon
    }

    private fun contentCover(
        pageId: String,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): MediaSource? {
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        while (true) {
            checkDeadline()
            val page =
                try {
                    client.fetchBlockChildrenPage(pageId, cursor)
                } catch (exception: SourceAccessException) {
                    logger.debug("Notion database content cover unavailable: {}", exception.javaClass.simpleName)
                    return null
                }
            checkDeadline()
            page.results.forEach {
                checkDeadline()
                reserve()
            }
            page.results.firstOrNull { !it.inTrash && it.type == "image" }?.let { return media(it.payload) }
            if (!page.hasMore) return null
            cursor = page.nextCursor?.takeIf(String::isNotBlank)
                ?: throw NotionBlockMappingException("cover block pagination cursor is missing")
            if (!cursors.add(cursor)) throw NotionBlockMappingException("cover block pagination cursor was repeated")
        }
    }

    private fun propertyCover(
        page: NotionPageResponse,
        propertyId: String,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): MediaSource? {
        if (!page.properties.isObject) throw NotionBlockMappingException("page properties must be an object")
        val properties = page.properties.filter { it.get("id")?.stringValue() == propertyId }
        if (properties.size > 1) throw NotionBlockMappingException("cover property id must be unique")
        val property = properties.singleOrNull() ?: return null
        if (property.text("type") != "files") throw NotionBlockMappingException("cover property type differs from its files schema")
        val hasMore = property.get("has_more")
        if (hasMore != null && !hasMore.isBoolean) throw NotionBlockMappingException("cover property has_more must be a boolean")
        if (hasMore?.booleanValue() == true) return null
        val files =
            property.get("files")?.takeIf(JsonNode::isArray)
                ?: throw NotionBlockMappingException("cover files property must contain an array")
        var firstImage: MediaSource? = null
        files.forEach { file ->
            checkDeadline()
            reserve()
            val source = media(file)
            val name = file.optionalText("name")
            if (firstImage == null && (isImageName(name) || isImageName(source.url().path))) firstImage = source
        }
        return firstImage
    }

    private fun media(payload: JsonNode): MediaSource {
        if (!payload.isObject) throw NotionBlockMappingException("cover media must be an object")
        validateExpiry(payload)
        val source = blockMapper.mapMediaSource(payload)
        validateSource(source)
        return source
    }

    private fun validateExpiry(payload: JsonNode) {
        val expiry = payload.get("file")?.takeIf(JsonNode::isObject)?.optionalText("expiry_time")
        if (expiry != null && expiry.isBlank()) throw NotionBlockMappingException("row media expiry must not be blank")
    }

    private fun validateSource(source: MediaSource) {
        val url = source.url()
        if (url.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || url.host.isNullOrBlank() || url.rawUserInfo != null) {
            throw NotionBlockMappingException("row media URL must be a public http or https URL")
        }
    }

    private fun MediaSource.url(): URI =
        when (this) {
            is MediaSource.External -> url
            is MediaSource.SourceHosted -> url
        }

    private fun isImageName(value: String?): Boolean = value?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in imageExtensions

    private fun JsonNode.text(field: String): String =
        optionalText(field)?.takeIf(String::isNotBlank)
            ?: throw NotionBlockMappingException("required cover property text is missing")

    private fun JsonNode.optionalText(field: String): String? {
        val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
        if (!value.isString) throw NotionBlockMappingException("cover property text must be a string")
        return value.stringValue()
    }

    private companion object {
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg")
        val logger = LoggerFactory.getLogger(NotionDataViewCoverReader::class.java)
    }
}
