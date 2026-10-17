package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPostsConfiguration
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionBlockMappingException
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionPostPropertiesMapper
import xyz.robinjoon.notionblog.blog.application.model.ImportedRevision
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId

/**
 * 검토를 위해 페이지 본문과 속성을 가져온다. 공개 공유, `public_url`, 부모 소속은 판단에 쓰지 않으며
 * 게시 여부는 애플리케이션만 결정한다. 휴지통의 페이지는 새로 고침을 멈추고 진단에 맡긴다.
 */
internal class NotionRevisionSource(
    sourceId: SourceId,
    posts: NotionPostsConfiguration,
    private val pages: NotionPostSource,
) : PostRevisionSource {
    /** 스프링 설정이 어댑터 DTO에 의존하지 않도록 값을 풀어 받는 생성자. */
    constructor(
        sourceId: SourceId,
        pages: NotionPostSource,
        dataSourceId: String,
        summaryPropertyId: String,
        tagsPropertyId: String,
    ) : this(sourceId, NotionPostsConfiguration(dataSourceId, summaryPropertyId, tagsPropertyId), pages)

    private val properties = NotionPostPropertiesMapper(sourceId, posts)

    override fun fetchRevision(reference: SourceDocumentRef): ImportedRevision {
        val collected =
            pages.fetch(reference) { page ->
                if (page.inTrash) throw SourceAccessException("Notion page is in the trash")
            }
        return try {
            ImportedRevision(
                sourceDocument = collected.metadata.sourceDocument,
                properties = properties.map(collected.page, collected.metadata.title),
                sourceRevision = collected.sourceRevision,
                content = collected.content,
            )
        } catch (exception: NotionBlockMappingException) {
            throw SourceMappingException("Notion page properties could not be mapped", exception)
        } catch (exception: IllegalArgumentException) {
            throw SourceMappingException("Notion page properties are malformed", exception)
        }
    }
}
