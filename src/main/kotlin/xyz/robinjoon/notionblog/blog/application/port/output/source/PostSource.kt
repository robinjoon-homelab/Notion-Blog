package xyz.robinjoon.notionblog.blog.application.port.output.source

import xyz.robinjoon.notionblog.blog.application.model.ImportedPost
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef

interface PostSource {
    fun fetch(reference: SourceDocumentRef): ImportedPost
}
