package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAuthenticationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException

internal object MigrationFailureDiagnostics {
    fun of(exception: RuntimeException): MigrationFailureDetail =
        when (exception) {
            is SourceException -> {
                exception.diagnostic
                    ?: MigrationFailureDetail(MigrationFailureStage.SOURCE, "Notion 데이터 조회 또는 변환을 완료하지 못했습니다.", sourceType(exception))
            }

            is MediaFetchException -> {
                exception.diagnostic
                    ?: MigrationFailureDetail(MigrationFailureStage.MEDIA, "첨부 파일을 보관하지 못했습니다.", "MediaFetchException")
            }

            is IllegalArgumentException -> {
                MigrationFailureDetail(MigrationFailureStage.IMPORT, "가져온 데이터가 블로그의 저장 조건과 맞지 않습니다.", "IllegalArgumentException")
            }

            else -> {
                MigrationFailureDetail(MigrationFailureStage.IMPORT, "글을 가져오는 중 확인되지 않은 오류가 발생했습니다.", "RuntimeException")
            }
        }

    private fun sourceType(exception: SourceException): String =
        when (exception) {
            is RetryableSourceException -> "RetryableSourceException"
            is SourceAccessException -> "SourceAccessException"
            is SourceAuthenticationException -> "SourceAuthenticationException"
            is SourceConfigurationException -> "SourceConfigurationException"
            is SourceMappingException -> "SourceMappingException"
        }
}
