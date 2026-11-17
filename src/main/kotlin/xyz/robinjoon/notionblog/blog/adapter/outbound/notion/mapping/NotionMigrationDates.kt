package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.zone.ZoneRulesException

internal object NotionMigrationDates {
    fun read(property: JsonNode): Instant {
        val type = property.requiredText("type")
        val date = if (type == "date") property.get("date") else null
        val value = if (type == "created_time") dateText(property.get(type)) else dateStart(date)
        return parse(value, date?.optionalText("time_zone"))
    }

    private fun dateStart(date: JsonNode?): String {
        if (date != null && !date.isNull && !date.isObject) throw failure("작성일의 date 값이 객체 형식이 아닙니다.")
        return dateText(date?.get("start"))
    }

    private fun dateText(value: JsonNode?): String {
        if (value == null || value.isNull || (value.isString && value.stringValue().isBlank())) throw failure("매핑한 작성일이 비어 있습니다.")
        if (!value.isString) throw failure("작성일의 날짜·시각 값이 문자열 형식이 아닙니다.")
        return value.stringValue()
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
            val type =
                when (exception) {
                    is DateTimeParseException -> "DateTimeParseException"
                    is ZoneRulesException -> "ZoneRulesException"
                    else -> "DateTimeException"
                }
            throw failure("작성일의 날짜·시각 형식 또는 시간대가 올바르지 않습니다.", exception, type)
        }

    private fun parseTimestamp(value: String): Instant {
        val parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(value, OffsetDateTime::from, LocalDateTime::from)
        return when (parsed) {
            is OffsetDateTime -> parsed.toInstant()
            is LocalDateTime -> parsed.toInstant(ZoneOffset.UTC)
            else -> throw failure("매핑한 작성일을 시각으로 변환할 수 없습니다.")
        }
    }

    private fun failure(
        reason: String,
        cause: Throwable? = null,
        type: String = "SourceMappingException",
    ): SourceMappingException =
        SourceMappingException(
            "Migration authored date could not be mapped",
            cause,
            MigrationFailureDetail(MigrationFailureStage.AUTHORED_AT, reason, type),
        )

    private val dateOnly = Regex("\\d{4}-\\d{2}-\\d{2}")
}
