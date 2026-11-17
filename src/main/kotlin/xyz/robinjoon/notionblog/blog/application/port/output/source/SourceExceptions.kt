package xyz.robinjoon.notionblog.blog.application.port.output.source

import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail

sealed class SourceException(
    message: String? = null,
    cause: Throwable? = null,
    val diagnostic: MigrationFailureDetail? = null,
) : RuntimeException(message, cause)

class RetryableSourceException(
    message: String? = null,
    cause: Throwable? = null,
    diagnostic: MigrationFailureDetail? = null,
) : SourceException(message, cause, diagnostic)

class SourceAuthenticationException(
    message: String? = null,
    cause: Throwable? = null,
    diagnostic: MigrationFailureDetail? = null,
) : SourceException(message, cause, diagnostic)

class SourceAccessException(
    message: String? = null,
    cause: Throwable? = null,
    diagnostic: MigrationFailureDetail? = null,
) : SourceException(message, cause, diagnostic)

class SourceConfigurationException(
    message: String? = null,
    cause: Throwable? = null,
    diagnostic: MigrationFailureDetail? = null,
) : SourceException(message, cause, diagnostic)

class SourceMappingException(
    message: String? = null,
    cause: Throwable? = null,
    diagnostic: MigrationFailureDetail? = null,
) : SourceException(message, cause, diagnostic)
