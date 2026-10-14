package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// UTC ISO-8601 at second precision, accepted by sitemaps, Open Graph, and schema.org dates alike.
internal fun Instant.toPublicTimestamp(): String = DateTimeFormatter.ISO_INSTANT.format(truncatedTo(ChronoUnit.SECONDS))
