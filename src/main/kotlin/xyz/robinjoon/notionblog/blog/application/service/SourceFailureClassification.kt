package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

internal fun SourceException.toSyncFailureKind(): SyncFailureKind =
    when (this) {
        is RetryableSourceException -> SyncFailureKind.RETRYABLE_SOURCE
        is SourceAuthenticationException -> SyncFailureKind.AUTHENTICATION
        is SourceAccessException -> SyncFailureKind.ACCESS
        is SourceConfigurationException -> SyncFailureKind.CONFIGURATION
        is SourceMappingException -> SyncFailureKind.MAPPING
    }
