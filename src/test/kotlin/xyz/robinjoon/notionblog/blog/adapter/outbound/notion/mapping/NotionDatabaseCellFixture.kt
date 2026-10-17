package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import java.net.URI

/** 데이터베이스 셀 매퍼 테스트들이 같은 페이지 응답과 셀 조립 방식을 쓰도록 한다. */
internal class NotionDatabaseCellFixture {
    val json = JsonMapper.builder().build()
    val mapper = NotionDatabaseCellMapper(NotionBlockMapper(SourceId("notion-main")), SourceId("notion-main"))

    fun cell(
        type: String,
        value: String,
        extra: String = "",
        publicUrl: String? = "https://example.com/public-page",
    ): List<InlineContent> =
        mapper
            .mapRow(
                page("""{"Value":{"id":"selected","type":"$type","$type":$value$extra}}""", publicUrl),
                listOf(NotionDatabaseProperty("selected", "Value", type)),
            ).cells
            .single()

    fun page(
        properties: String,
        publicUrl: String? = "https://example.com/public-page",
    ) = NotionPageResponse(
        id = "a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d",
        parent = NotionPageParentResponse("data_source_id", null),
        url = "https://www.notion.so/private-page",
        publicUrl = publicUrl,
        inTrash = false,
        lastEditedTime = "2026-08-31T12:00:00Z",
        properties = json.readTree(properties),
    )

    fun pageLink(publicUrl: String? = "https://example.com/public-page"): LinkTarget.SourceDocument =
        LinkTarget.SourceDocument(
            SourceDocumentRef(SourceId("notion-main"), "a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d"),
            publicUrl?.let(::URI),
        )

    fun richText(value: String): String =
        """{"type":"text","text":{"content":${json.writeValueAsString(value)},"link":null},"annotations":{}}"""

    fun label(cell: List<InlineContent>): String =
        cell.joinToString("") {
            when (it) {
                is InlineContent.Text -> it.text
                is InlineContent.Equation -> it.expression
                is InlineContent.Mention -> it.label
            }
        }
}
