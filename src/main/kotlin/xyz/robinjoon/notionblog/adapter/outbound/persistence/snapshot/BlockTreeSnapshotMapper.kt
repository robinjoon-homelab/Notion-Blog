package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent

internal class BlockTreeSnapshotMapper {
    fun toJson(tree: BlockTree): ObjectNode =
        objectNode().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("kind", DOCUMENT_KIND)
            set("blocks", jsonArray(tree.roots, ::toJson))
        }

    fun fromJson(document: JsonNode): BlockTree {
        val root = document.requireObject("snapshot document")
        val schemaVersion = root.requiredInt("schemaVersion", "snapshot document")
        require(schemaVersion == SCHEMA_VERSION) { "unsupported block tree snapshot schema version: $schemaVersion" }
        require(root.requiredText("kind", "snapshot document") == DOCUMENT_KIND) { "invalid block tree snapshot kind" }
        return BlockTree(root.requiredArray("blocks", "snapshot document").toList().map(::fromJsonNode))
    }

    private fun toJson(node: BlockNode): ObjectNode =
        objectNode().apply {
            put("id", node.id.value)
            put("kind", kindOf(node.content))
            set("style", toJson(node.style))
            set("content", toJsonContent(node.content))
            set("children", jsonArray(node.children, ::toJson))
        }

    private fun fromJsonNode(node: JsonNode): BlockNode {
        val objectNode = node.requireObject("block")
        val kind = objectNode.requiredText("kind", "block")
        val children = objectNode.requiredArray("children", "block").toList().map(::fromJsonNode)
        return BlockNode(
            id = BlockId(objectNode.requiredText("id", "block")),
            content =
                if (kind.isKnownBlockKind()) {
                    fromJsonContent(kind, objectNode.requiredObject("content", "$kind block"))
                } else {
                    UnsupportedBlockContent(kind)
                },
            style = fromJsonStyle(objectNode.requiredObject("style", "$kind block")),
            children = children,
        )
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val DOCUMENT_KIND = "block_tree_snapshot"
    }
}
