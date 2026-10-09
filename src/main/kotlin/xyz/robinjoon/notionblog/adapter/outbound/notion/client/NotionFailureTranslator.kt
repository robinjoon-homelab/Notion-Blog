package xyz.robinjoon.notionblog.adapter.outbound.notion.client

import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.application.port.output.source.SourceException

internal class NotionFailureTranslator {
    fun httpFailure(
        statusCode: Int,
        cause: Throwable? = null,
    ): SourceException =
        when {
            statusCode == TOO_MANY_REQUESTS || statusCode >= SERVER_ERROR_START -> {
                RetryableSourceException("Notion source request failed with HTTP $statusCode", cause)
            }

            statusCode == UNAUTHORIZED || statusCode == FORBIDDEN -> {
                SourceAuthenticationException("Notion source authentication failed with HTTP $statusCode", cause)
            }

            statusCode == NOT_FOUND -> {
                SourceAccessException("Notion source object could not be accessed", cause)
            }

            else -> {
                SourceConfigurationException("Notion source rejected a request with HTTP $statusCode", cause)
            }
        }

    fun requestFailure(cause: Throwable): RetryableSourceException =
        RetryableSourceException("Notion source request could not be completed", cause)

    fun invalidResponse(cause: Throwable? = null): SourceConfigurationException =
        SourceConfigurationException("Notion source returned an invalid response", cause)

    fun collectionDeadlineExceeded(): RetryableSourceException = RetryableSourceException("Notion source collection deadline exceeded")

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR_START = 500
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
    }
}
