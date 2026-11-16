package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// 이전한 글의 원본 속성 매핑이다. 이후 새로고침도 같은 필드를 읽으며 기존 매핑을 덮어쓰지 않는다.
interface PostMigrationMappingRepository {
    fun find(sourceDocument: SourceDocumentRef): MigrationSelection?

    fun save(
        postId: PostId,
        selection: MigrationSelection,
    )
}
