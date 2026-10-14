package xyz.robinjoon.notionblog.blog.application.port.output.source

sealed class SourceException(
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class RetryableSourceException(
    message: String? = null,
    cause: Throwable? = null,
) : SourceException(message, cause)

class SourceAuthenticationException(
    message: String? = null,
    cause: Throwable? = null,
) : SourceException(message, cause)

class SourceAccessException(
    message: String? = null,
    cause: Throwable? = null,
) : SourceException(message, cause)

class SourceConfigurationException(
    message: String? = null,
    cause: Throwable? = null,
) : SourceException(message, cause)

class SourceMappingException(
    message: String? = null,
    cause: Throwable? = null,
) : SourceException(message, cause)
