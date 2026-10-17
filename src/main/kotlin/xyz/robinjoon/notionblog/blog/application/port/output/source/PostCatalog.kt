package xyz.robinjoon.notionblog.blog.application.port.output.source

import xyz.robinjoon.notionblog.blog.application.model.CatalogEntry
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import java.net.URI

interface PostCatalog {
    // 글 컬렉션의 모든 문서를 반환하거나 SourceException을 던진다. 일부만 담은 목록은 반환하지 않는다.
    fun scan(): List<CatalogEntry>

    fun editUrl(sourceDocument: SourceDocumentRef): URI
}
