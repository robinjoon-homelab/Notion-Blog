package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource

// 리비전의 태그와 커버다. 커버는 블록 스냅숏과 같은 논리 미디어 JSON 형식을 쓴다.
class JsonPostPropertiesCodec(
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    fun encodeTags(tags: List<String>): String = mapper.writeValueAsString(jsonArray(tags, ::jsonString))

    fun decodeTags(tagsJson: String): List<String> =
        read(tagsJson)
            .requireArray("revision tags")
            .values()
            .map { tag -> requireNotNull(tag.takeIf(JsonNode::isString)?.stringValue()) { "revision tags must be strings" } }

    fun encodeCover(cover: MediaSource?): String? = cover?.let { mapper.writeValueAsString(toJsonMediaSource(it)) }

    fun decodeCover(coverJson: String?): MediaSource? = coverJson?.let { fromJsonMediaSource(read(it).requireObject("revision cover")) }

    private fun read(json: String): JsonNode =
        try {
            mapper.readTree(json)
        } catch (exception: JacksonException) {
            throw IllegalArgumentException("invalid revision property JSON", exception)
        }
}
