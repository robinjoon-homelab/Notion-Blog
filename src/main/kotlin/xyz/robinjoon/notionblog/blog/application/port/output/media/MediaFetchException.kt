package xyz.robinjoon.notionblog.blog.application.port.output.media

import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail

class MediaFetchException(
    message: String,
    cause: Throwable? = null,
    val diagnostic: MigrationFailureDetail? = null,
) : RuntimeException(message, cause)
