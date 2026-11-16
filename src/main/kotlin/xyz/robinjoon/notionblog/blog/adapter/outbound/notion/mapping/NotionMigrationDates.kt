package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object NotionMigrationDates {
    fun read(property: JsonNode): Instant {
        val type = property.requiredText("type")
        val date = if (type == "date") property.optionalObject("date") else null
        val value = if (type == "created_time") property.optionalText(type) else date?.optionalText("start")
        if (value == null) throw SourceMappingException("Mapped migration authored date is empty")
        return parse(value, date?.optionalText("time_zone"))
    }

    private fun parse(
        value: String,
        timeZone: String?,
    ): Instant =
        try {
            when {
                dateOnly.matches(value) -> LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
                timeZone != null -> LocalDateTime.parse(value).atZone(ZoneId.of(timeZone)).toInstant()
                else -> parseTimestamp(value)
            }
        } catch (exception: DateTimeException) {
            throw SourceMappingException("Mapped migration authored date is malformed", exception)
        }

    private fun parseTimestamp(value: String): Instant {
        val parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(value, OffsetDateTime::from, LocalDateTime::from)
        return when (parsed) {
            is OffsetDateTime -> parsed.toInstant()
            is LocalDateTime -> parsed.toInstant(ZoneOffset.UTC)
            else -> throw SourceMappingException("Mapped migration authored date is not a timestamp")
        }
    }

    private val dateOnly = Regex("\\d{4}-\\d{2}-\\d{2}")
}
