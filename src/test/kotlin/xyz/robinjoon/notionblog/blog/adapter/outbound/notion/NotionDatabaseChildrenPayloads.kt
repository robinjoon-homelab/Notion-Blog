package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId

internal object NotionDatabaseChildrenPayloads {
    const val ROOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val DATABASE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val SOURCE = "cccccccccccccccccccccccccccccccc"
    const val SECOND_SOURCE = "dddddddddddddddddddddddddddddddd"
    const val VIEW = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    const val QUERY = "ffffffffffffffffffffffffffffffff"
    const val ROW = "11111111111111111111111111111111"
    const val PRIVATE_ROW = "22222222222222222222222222222222"
    const val HIDDEN_ROW = "33333333333333333333333333333333"
    const val TRASH_ROW = "44444444444444444444444444444444"
    const val SECOND_ROW = "55555555555555555555555555555555"
    const val FOREIGN = "66666666666666666666666666666666"
    const val CONTAINER = "77777777777777777777777777777777"
    const val SYNCED = "88888888888888888888888888888888"

    val sourceId = SourceId("notion-main")

    fun database(
        parent: String = parent("page_id", ROOT),
        sources: List<String> = listOf(SOURCE),
    ) = """{"object":"database","id":"$DATABASE","parent":$parent,"data_sources":[${sources.joinToString(
        ",",
    ) {
        """{"id":"$it","name":"Posts"}"""
    }}],"title":[{"plain_text":"Posts"}],"url":"https://www.notion.so/$DATABASE","in_trash":false}"""

    fun dataSource(
        id: String,
        parent: String = parent("database_id", DATABASE),
    ) = """{"object":"data_source","id":"$id","parent":$parent,"in_trash":false,
          "properties":{"Name":{"id":"title","name":"Name","type":"title"}}}"""

    fun page(
        id: String,
        parent: String = parent("data_source_id", SOURCE),
        publicUrl: String = "\"https://site.notion.site/$id\"",
        inTrash: Boolean = false,
    ) = """{"object":"page","id":"$id","parent":$parent,"url":"https://www.notion.so/$id",
          "public_url":$publicUrl,"in_trash":$inTrash,"last_edited_time":"2026-10-05T00:00:00Z",
          "properties":{"Name":{"id":"title","type":"title",
            "title":[{"type":"text","text":{"content":"$id"},"plain_text":"$id","annotations":{}}]}}}"""

    fun parent(
        type: String,
        id: String = ROOT,
    ) = if (type ==
        "workspace"
    ) {
        """{"type":"workspace","workspace":true}"""
    } else {
        """{"type":"$type","$type":"$id"}"""
    }

    fun databaseBlock() =
        """{"id":"$DATABASE","type":"child_database","has_children":true,"in_trash":false,"child_database":{"title":"Posts"}}"""

    fun containerBlock() = """{"id":"$CONTAINER","type":"toggle","has_children":true,"in_trash":false,"toggle":{"rich_text":[]}}"""

    fun blockMetadata(
        id: String,
        parent: String,
    ) = """{"object":"block","id":"$id","parent":$parent,"type":"toggle","has_children":true,"in_trash":false,"toggle":{"rich_text":[]}}"""

    fun view(type: String) =
        """{"object":"view","id":"$VIEW","parent":{"type":"database_id","database_id":"$DATABASE"},
          "name":"Shown","type":"$type","data_source_id":"$SOURCE"}"""

    fun viewsList(vararg entries: String) =
        """{"object":"list","type":"view","view":{},"results":[${entries.joinToString(",")}],"has_more":false,"next_cursor":null}"""

    fun list(
        vararg entries: String,
        cursor: String? = null,
    ) = """{"object":"list","results":[${entries.joinToString(
        ",",
    )}],"has_more":${cursor != null},"next_cursor":${cursor?.let { "\"$it\"" } ?: "null"}}"""

    fun reference() = SourceDocumentRef(sourceId, ROOT)
}
