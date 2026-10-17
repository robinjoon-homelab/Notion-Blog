package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal fun Instant.asOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
