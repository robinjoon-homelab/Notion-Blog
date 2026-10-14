package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.SynchronizedBlockOrigin

internal fun toJsonOrigin(origin: SynchronizedBlockOrigin): ObjectNode =
    objectNode().apply {
        set("document", toJsonReference(origin.document))
        put("blockExternalId", origin.blockExternalId)
    }

internal fun fromJsonOrigin(node: ObjectNode): SynchronizedBlockOrigin =
    SynchronizedBlockOrigin(
        fromJsonReference(node.requiredObject("document", "synchronized block origin")),
        node.requiredText("blockExternalId", "synchronized block origin"),
    )
