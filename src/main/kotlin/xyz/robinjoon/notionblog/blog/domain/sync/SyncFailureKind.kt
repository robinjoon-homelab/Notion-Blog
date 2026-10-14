package xyz.robinjoon.notionblog.blog.domain.sync

enum class SyncFailureKind {
    RETRYABLE_SOURCE,
    AUTHENTICATION,
    ACCESS,
    CONFIGURATION,
    MAPPING,
}
