package xyz.robinjoon.notionblog.adapter.output.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.net.URISyntaxException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal class NotionDatabaseCellMapper(
    private val blockMapper: NotionBlockMapper,
    private val sourceId: SourceId,
) {
    fun mapRow(page: NotionPageResponse, columns: List<NotionDatabaseProperty>): DataRow {
        if (!page.properties.isObject) throw NotionBlockMappingException("page properties must be an object")
        val pageLink = LinkTarget.SourceDocument(
            SourceDocumentRef(sourceId, NotionIdNormalizer.normalize(page.id)),
            page.publicUrl?.let(::safeUrl)?.url,
        )
        return DataRow(
            columns.map { column ->
                val properties = page.properties.filter { it.get("id")?.stringValue() == column.id }
                if (properties.size > 1) throw NotionBlockMappingException("selected property id must be unique")
                val property = properties.singleOrNull()
                if (property == null) {
                    incomplete()
                } else {
                    if (property.requiredText("type") != column.type) {
                        throw NotionBlockMappingException("selected property type differs from its schema")
                    }
                    if (property.hasMore()) incomplete() else mapValue(column.type, property.path(column.type), pageLink)
                }
            },
            link = pageLink,
        )
    }

    private fun mapValue(type: String, value: JsonNode, pageLink: LinkTarget.SourceDocument): List<InlineContent> {
        if (value.isNull) return emptyList()
        return when (type) {
            "title", "rich_text" -> richText(type, value, pageLink)

            "number" -> text(number(value))

            "checkbox" -> text(boolean(value))

            "select", "status" -> text(value.objectValue().requiredText("name"))

            "multi_select" -> text(value.arrayValue().joinToString(", ") { it.objectValue().requiredText("name") })

            "date" -> text(date(value))

            "url" -> value.requireTextValue().let { text(it, safeUrl(it)) }

            "email", "phone_number" -> text(value.requireTextValue())

            "created_time", "last_edited_time" -> text(timestamp(value.requireTextValue()))

            "unique_id" -> text(uniqueId(value))

            "people" -> {
                val people = value.arrayValue()
                val names = people.map(::displayName)
                if (people.size >= 25) incomplete() else text(names.joinToString(", "))
            }

            "created_by", "last_edited_by" -> text(displayName(value))

            "files" -> text(value.arrayValue().joinToString(", ", transform = ::displayName))

            "formula", "rollup" -> calculation(value)

            "relation" -> {
                val references = value.arrayValue()
                references.forEach { it.objectValue().requiredText("id") }
                when {
                    references.isEmpty() -> emptyList()
                    references.size >= 25 -> incomplete()
                    else -> unsupported()
                }
            }

            else -> unsupported()
        }
    }

    private fun richText(type: String, value: JsonNode, pageLink: LinkTarget.SourceDocument): List<InlineContent> {
        val richText = try {
            blockMapper.mapRichText(value)
        } catch (_: URISyntaxException) {
            throw NotionBlockMappingException("rich text contains an invalid URL")
        }
        val referenceCount = value.count { entry ->
            entry.get("type")?.stringValue() == "mention" &&
                entry.get("mention")?.get("type")?.stringValue() in setOf("page", "user")
        }
        if (referenceCount >= 25) return incomplete()
        return if (type == "title") {
            richText.map { if (it is InlineContent.Text) it.copy(link = pageLink) else it }
        } else {
            richText
        }
    }

    private fun calculation(value: JsonNode): List<InlineContent> {
        val calculation = value.objectValue()
        if (calculation.hasMore()) return incomplete()
        val resultType = calculation.requiredText("type")
        return when (resultType) {
            "number", "boolean", "string", "date" -> {
                val result = calculation.value(resultType)
                if (result.isNull) {
                    emptyList()
                } else {
                    text(
                        when (resultType) {
                            "number" -> number(result)
                            "boolean" -> boolean(result)
                            "date" -> date(result)
                            else -> result.requireTextValue()
                        },
                    )
                }
            }

            "array" -> {
                val items = calculation.value("array").arrayValue()
                if (items.size >= 25) incomplete() else unsupported()
            }

            "incomplete" -> incomplete()

            else -> unsupported()
        }
    }

    private fun number(value: JsonNode): String {
        if (!value.isNumber) throw NotionBlockMappingException("number property must contain a number")
        return value.decimalValue().stripTrailingZeros().toPlainString()
    }

    private fun boolean(value: JsonNode): String {
        if (!value.isBoolean) throw NotionBlockMappingException("boolean property must contain a boolean")
        return value.booleanValue().toString()
    }

    private fun date(value: JsonNode): String {
        val date = value.objectValue()
        val start = date.requiredText("start").also(::validateDate)
        val end = date.optionalText("end")?.also(::validateDate)
        val range = if (end == null) start else "$start – $end"
        val zone = date.optionalText("time_zone")?.also { timeZone ->
            try {
                ZoneId.of(timeZone)
            } catch (_: DateTimeException) {
                throw NotionBlockMappingException("date property contains an invalid time zone")
            }
        }
        return if (zone == null) range else "$range ($zone)"
    }

    private fun validateDate(value: String) {
        try {
            if ('T' in value) DateTimeFormatter.ISO_DATE_TIME.parse(value) else LocalDate.parse(value)
        } catch (exception: DateTimeParseException) {
            throw NotionBlockMappingException("date property contains an invalid date", exception)
        }
    }

    private fun timestamp(value: String): String {
        try {
            Instant.parse(value)
        } catch (exception: DateTimeParseException) {
            throw NotionBlockMappingException("time property contains an invalid timestamp", exception)
        }
        return value
    }

    private fun uniqueId(value: JsonNode): String {
        val uniqueId = value.objectValue()
        val number = uniqueId.value("number")
        if (!number.isIntegralNumber || number.bigIntegerValue().signum() < 0) {
            throw NotionBlockMappingException("unique id number must be a nonnegative integer")
        }
        val prefix = uniqueId.optionalText("prefix")?.takeIf(String::isNotBlank)
        return listOfNotNull(prefix, number.bigIntegerValue().toString()).joinToString("-")
    }

    private fun displayName(value: JsonNode): String = value.objectValue().optionalText("name")
        ?.takeIf(String::isNotBlank)
        ?: "[Name unavailable]"

    private fun safeUrl(value: String): LinkTarget.ExternalUrl? {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        return uri.takeIf {
            it.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                !it.host.isNullOrBlank() && it.rawUserInfo == null
        }?.let(LinkTarget::ExternalUrl)
    }

    private fun text(value: String, link: LinkTarget.ExternalUrl? = null): List<InlineContent> = if (value.isEmpty()) emptyList() else listOf(InlineContent.Text(value, link = link))

    private fun incomplete(): List<InlineContent> = text("[Incomplete property value]")

    private fun unsupported(): List<InlineContent> = text("[Unsupported property value]")

    private fun JsonNode.hasMore(): Boolean {
        val value = get("has_more") ?: return false
        if (!value.isBoolean) throw NotionBlockMappingException("property has_more must be a boolean")
        return value.booleanValue()
    }

    private fun JsonNode.value(field: String): JsonNode = get(field)
        ?: throw NotionBlockMappingException("property value is missing")

    private fun JsonNode.objectValue(): JsonNode {
        if (!isObject) throw NotionBlockMappingException("property value must be an object")
        return this
    }

    private fun JsonNode.arrayValue(): List<JsonNode> {
        if (!isArray) throw NotionBlockMappingException("property value must be an array")
        return toList()
    }

    private fun JsonNode.requireTextValue(): String {
        if (!isString) throw NotionBlockMappingException("property value must be text")
        return stringValue()
    }

    private fun JsonNode.optionalText(field: String): String? = get(field)?.takeUnless(JsonNode::isNull)?.requireTextValue()

    private fun JsonNode.requiredText(field: String): String = optionalText(field)
        ?.takeIf(String::isNotBlank)
        ?: throw NotionBlockMappingException("required property text is missing")
}
