package xyz.robinjoon.notionblog.blog.domain.post.block.media

import java.net.URI

// 앱이 보관한 파일 바이트의 SHA-256 소문자 16진수다.
@JvmInline
value class MediaKey(
    val value: String,
) {
    init {
        require(pattern.matches(value)) { "media key must be a lowercase SHA-256 hex digest" }
    }

    private companion object {
        val pattern = Regex("^[0-9a-f]{64}$")
    }
}

// 서명 query와 fragment를 뺀 원본 파일 URL이다. 서명이 바뀌어도 같은 Notion 파일은 같은 키를 가진다.
@JvmInline
value class SourceMediaKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "source media key must not be blank" }
    }

    companion object {
        // V8의 post_revision_media.source_key는 시작 시 MigrateLegacyPostsService의 레거시 복사를 포함해 모두 이 함수로 만든다.
        // 규칙을 바꾸면 이미 저장된 키와 새로 가져온 파일이 맞지 않는다.
        fun of(url: URI): SourceMediaKey = SourceMediaKey(url.toString().substringBefore('#').substringBefore('?'))
    }
}
