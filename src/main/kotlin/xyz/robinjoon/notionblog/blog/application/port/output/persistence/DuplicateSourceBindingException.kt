package xyz.robinjoon.notionblog.blog.application.port.output.persistence

class DuplicateSourceBindingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
