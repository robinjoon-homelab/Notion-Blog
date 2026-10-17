package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.CatalogSynchronization
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizeCatalogUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostCatalog

// 글 컬렉션에 나타난 문서를 등록한다. 스캔 결과에서 빠진 문서는 그대로 둔다.
class SynchronizeCatalogService(
    private val catalog: PostCatalog,
    private val registrations: RegisterPostService,
) : SynchronizeCatalogUseCase {
    override fun synchronize(): CatalogSynchronization = registrations.registerCatalog(catalog.scan())
}
