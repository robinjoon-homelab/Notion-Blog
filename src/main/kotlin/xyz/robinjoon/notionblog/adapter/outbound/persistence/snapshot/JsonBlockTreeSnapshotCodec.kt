package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.domain.post.block.BlockTree

class JsonBlockTreeSnapshotCodec(
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    private val blockTreeMapper = BlockTreeSnapshotMapper()

    fun encode(tree: BlockTree): String = mapper.writeValueAsString(blockTreeMapper.toJson(tree))

    fun decode(snapshotJson: String): BlockTree =
        try {
            blockTreeMapper.fromJson(mapper.readTree(snapshotJson))
        } catch (exception: JacksonException) {
            throw IllegalArgumentException("invalid block tree snapshot", exception)
        }
}
