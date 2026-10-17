package xyz.robinjoon.notionblog.blog.application.port.output.media

class MediaFetchException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
