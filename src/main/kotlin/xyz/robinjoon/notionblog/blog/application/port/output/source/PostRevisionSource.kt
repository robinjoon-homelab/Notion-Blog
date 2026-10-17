package xyz.robinjoon.notionblog.blog.application.port.output.source

import xyz.robinjoon.notionblog.blog.application.model.ImportedRevision
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef

interface PostRevisionSource {
    // 실패하면 SourceException을 던진다. 원본 상태는 게시 여부를 결정하지 않는다.
    fun fetchRevision(reference: SourceDocumentRef): ImportedRevision
}
