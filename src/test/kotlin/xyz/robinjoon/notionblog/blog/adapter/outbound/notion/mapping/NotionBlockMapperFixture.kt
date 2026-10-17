package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.domain.SourceId

/** 블록 매퍼 테스트들이 같은 소스 식별자와 블록 응답 조립 방식을 쓰도록 한다. */
internal class NotionBlockMapperFixture {
    val objectMapper = JsonMapper.builder().build()
    val mapper = NotionBlockMapper(SourceId("notion-main"))

    fun envelope(
        type: String,
        payload: String,
        id: String = "block-$type",
    ): NotionBlockEnvelope =
        NotionBlockEnvelope(
            id = id,
            type = type,
            hasChildren = false,
            inTrash = false,
            payload = objectMapper.readTree(payload),
        )
}
