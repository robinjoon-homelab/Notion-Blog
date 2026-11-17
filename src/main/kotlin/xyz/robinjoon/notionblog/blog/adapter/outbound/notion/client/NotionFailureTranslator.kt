package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client

import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import javax.net.ssl.SSLException

internal class NotionFailureTranslator {
    fun httpFailure(
        statusCode: Int,
        cause: Throwable? = null,
    ): SourceException =
        when {
            statusCode == TOO_MANY_REQUESTS || statusCode == CONFLICT || statusCode >= SERVER_ERROR_START -> {
                RetryableSourceException("Notion source request failed with HTTP $statusCode", cause, httpDetail(statusCode, cause))
            }

            statusCode == UNAUTHORIZED || statusCode == FORBIDDEN -> {
                SourceAuthenticationException(
                    "Notion source authentication failed with HTTP $statusCode",
                    cause,
                    httpDetail(statusCode, cause),
                )
            }

            statusCode == NOT_FOUND -> {
                SourceAccessException("Notion source object could not be accessed", cause, httpDetail(statusCode, cause))
            }

            else -> {
                SourceConfigurationException("Notion source rejected a request with HTTP $statusCode", cause, httpDetail(statusCode, cause))
            }
        }

    fun requestFailure(cause: Throwable): RetryableSourceException =
        RetryableSourceException(
            "Notion source request could not be completed",
            cause,
            detail(requestReason(cause), cause),
        )

    fun invalidResponse(cause: Throwable? = null): SourceConfigurationException =
        SourceConfigurationException(
            "Notion source returned an invalid response",
            cause,
            detail("Notion 응답 형식이 예상과 다르거나 응답을 해석하지 못했습니다.", cause),
        )

    fun collectionDeadlineExceeded(): RetryableSourceException =
        RetryableSourceException(
            "Notion source collection deadline exceeded",
            diagnostic = detail("Notion 문서 전체를 가져오는 제한 시간이 초과되었습니다."),
        )

    private fun httpDetail(
        statusCode: Int,
        cause: Throwable?,
    ): MigrationFailureDetail {
        val reason =
            when (statusCode) {
                UNAUTHORIZED -> "Notion 연동 토큰이 유효하지 않거나 인증이 만료되었습니다."
                FORBIDDEN -> "Notion 연동에 이 데이터베이스 또는 문서를 읽을 권한이 없습니다."
                NOT_FOUND -> "Notion 원본을 찾을 수 없습니다. 링크와 연동의 접근 권한을 확인해 주세요."
                TOO_MANY_REQUESTS -> "Notion API 요청 한도를 초과했습니다. 잠시 뒤 다시 시도해 주세요."
                CONFLICT -> "Notion에서 요청 충돌이 발생했습니다. 다시 시도해 주세요."
                else -> "Notion API가 요청을 처리하지 못했습니다."
            }
        return detail(reason, cause).copy(httpStatus = statusCode.takeIf { it in httpStatusRange })
    }

    private fun detail(
        reason: String,
        cause: Throwable? = null,
    ): MigrationFailureDetail =
        MigrationFailureDetail(MigrationFailureStage.SOURCE, reason, cause?.javaClass?.simpleName ?: "SourceException")

    private fun requestReason(cause: Throwable): String =
        generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).mapNotNull(::knownRequestReason).firstOrNull()
            ?: "Notion 서버와 통신 중 연결이 끊기거나 요청이 실패했습니다."

    private fun knownRequestReason(cause: Throwable): String? =
        when (cause) {
            is HttpTimeoutException, is SocketTimeoutException -> "Notion 서버 응답 대기 시간이 초과되었습니다."
            is UnknownHostException -> "Notion 서버의 DNS 주소를 확인하지 못했습니다."
            is SSLException -> "Notion 서버와 보안 연결을 맺지 못했습니다."
            is ConnectException -> "Notion 서버에 연결하지 못했습니다."
            is InterruptedException -> "Notion 요청이 중단되었습니다."
            else -> null
        }

    private companion object {
        const val MAX_CAUSE_DEPTH = 16
        val httpStatusRange = 100..599
        const val TOO_MANY_REQUESTS = 429
        const val CONFLICT = 409
        const val SERVER_ERROR_START = 500
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
    }
}
