package xyz.robinjoon.notionblog.blog.domain.sync

enum class SyncFailureKind {
    RETRYABLE_SOURCE,
    AUTHENTICATION,
    ACCESS,
    CONFIGURATION,
    MAPPING,

    // 새 리비전의 파일을 복사하지 못해 리비전을 저장하지 않았다.
    MEDIA,
}
