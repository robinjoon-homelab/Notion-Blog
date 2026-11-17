package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException

internal fun mediaFailure(
    message: String,
    reason: String,
    cause: Throwable? = null,
): MediaFetchException =
    MediaFetchException(
        message,
        cause,
        MigrationFailureDetail(MigrationFailureStage.MEDIA, reason, cause?.javaClass?.simpleName ?: "MediaFetchException"),
    )
