package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPostsConfiguration
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionPageMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionPostSchema
import xyz.robinjoon.notionblog.blog.application.model.CatalogEntry
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostCatalog
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import java.net.URI

/**
 * 글 데이터 소스의 직접 행을 조회한다. 스캔은 모든 행을 반환하거나 예외를 던지며,
 * 이 어댑터는 스캔에서 빠진 행을 삭제로 취급하지 않는다. 노션 페이지는 노션에서 직접 만들고 수정하므로 쓰기는 하지 않는다.
 */
internal class NotionPostCatalog(
    private val sourceId: SourceId,
    private val client: NotionApiClient,
    private val posts: NotionPostsConfiguration,
) : PostCatalog {
    /** 스프링 설정이 어댑터 DTO에 의존하지 않도록 값을 풀어 받는 생성자. */
    constructor(
        sourceId: SourceId,
        client: NotionApiClient,
        dataSourceId: String,
        summaryPropertyId: String,
        tagsPropertyId: String,
    ) : this(sourceId, client, NotionPostsConfiguration(dataSourceId, summaryPropertyId, tagsPropertyId))

    private val pageMapper = NotionPageMapper()

    override fun scan(): List<CatalogEntry> {
        val dataSourceId = configuredDataSourceId()
        val schema = NotionPostSchema.validate(client.fetchDataSource(dataSourceId), posts)
        val entries = linkedMapOf<String, CatalogEntry>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val page = client.queryPostRows(dataSourceId, listOf(schema.titlePropertyId), cursor)
            page.results.filterNot(NotionPageResponse::inTrash).forEach { row ->
                val entry = entry(row)
                entries.putIfAbsent(entry.sourceDocument.externalId, entry)
            }
            cursor = page.nextCursor
            if (cursor != null && !seenCursors.add(cursor)) {
                throw SourceMappingException("Notion data source pagination did not advance")
            }
        } while (cursor != null)
        return entries.values.toList()
    }

    override fun editUrl(sourceDocument: SourceDocumentRef): URI =
        URI.create("https://www.notion.so/${NotionIdNormalizer.normalize(sourceDocument.externalId)}")

    private fun entry(row: NotionPageResponse): CatalogEntry {
        val reference = SourceDocumentRef(sourceId, NotionIdNormalizer.normalize(row.id))
        return CatalogEntry(reference, pageMapper.map(row, reference).title)
    }

    private fun configuredDataSourceId(): String =
        posts.dataSourceId.takeIf(String::isNotBlank)
            ?: throw SourceConfigurationException("Notion posts data source is not configured")
}
