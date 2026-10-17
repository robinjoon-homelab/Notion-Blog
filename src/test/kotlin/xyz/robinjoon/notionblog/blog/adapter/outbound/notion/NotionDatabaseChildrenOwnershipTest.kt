package xyz.robinjoon.notionblog.blog.adapter.outbound.notion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.CONTAINER
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.DATABASE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.FOREIGN
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.ROOT
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.ROW
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.SOURCE
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.SYNCED
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.blockMetadata
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.containerBlock
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.dataSource
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.database
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.databaseBlock
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.list
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.parent
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.reference
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.NotionDatabaseChildrenPayloads.sourceId
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef

class NotionDatabaseChildrenOwnershipTest {
    private val fixture = NotionDatabaseChildrenServer()
    private val responses = fixture.responses
    private val queryPages = fixture.queryPages
    private val deniedPaths = fixture.deniedPaths

    @BeforeEach
    fun startServer() {
        fixture.start()
    }

    @AfterEach
    fun stopServer() {
        fixture.stop()
    }

    @ParameterizedTest
    @ValueSource(strings = ["page_id", "workspace", "data_source_id"])
    fun `does not discover pages from a database owned outside the current page`(type: String) {
        responses["/v1/databases/$DATABASE"] = database(parent(type, FOREIGN))

        assertThat(fixture.source().fetch(reference()).containedChildren).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["database_id", "data_source_id"])
    fun `does not discover pages from a linked source owned elsewhere`(type: String) {
        responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE, parent(type, FOREIGN))

        assertThat(fixture.source().fetch(reference()).containedChildren).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["page_id", "workspace", "block_id"])
    fun `rejects parent shapes that cannot own a data source`(type: String) {
        responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE, parent(type, FOREIGN))

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["database", "ancestor-database", "ancestor-workspace"])
    fun `rejects known parent kinds that cannot own the database or its ancestor block`(stage: String) {
        if (stage == "database") {
            responses["/v1/databases/$DATABASE"] = database(parent("database_id", FOREIGN))
        } else {
            responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
            val parentType = if (stage == "ancestor-database") "database_id" else "workspace"
            responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent(parentType, FOREIGN))
        }

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `includes pages of a nested database whose real block ancestor is the current page`() {
        responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
        responses["/v1/blocks/$ROOT/children"] = list(containerBlock())
        responses["/v1/blocks/$CONTAINER/children"] = list(databaseBlock())
        responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("page_id", ROOT))

        assertThat(fixture.source().fetch(reference()).containedChildren).containsExactly(SourceDocumentRef(sourceId, ROW))
    }

    @Test
    fun `does not mistake a visited external synced ancestor for structural ownership`() {
        responses["/v1/blocks/$ROOT/children"] =
            list(
                """{"id":"$SYNCED","type":"synced_block","has_children":true,"in_trash":false,
                  "synced_block":{"synced_from":{"type":"block_id","block_id":"$CONTAINER"}}}""",
            )
        responses["/v1/blocks/$SYNCED/children"] = list()
        responses["/v1/blocks/$CONTAINER/children"] = list(databaseBlock())
        responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
        responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("page_id", FOREIGN))

        assertThat(fixture.source().fetch(reference()).containedChildren).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["database", "source", "ancestor"])
    fun `fails unsupported ownership instead of silently shrinking children`(stage: String) {
        when (stage) {
            "database" -> {
                responses["/v1/databases/$DATABASE"] = database(parent("future_parent"))
            }

            "source" -> {
                responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE, parent("future_parent"))
            }

            else -> {
                responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
                responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("future_parent"))
            }
        }

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["database", "source", "ancestor"])
    fun `rejects malformed foreign parent ids instead of excluding ownership`(stage: String) {
        when (stage) {
            "database" -> {
                responses["/v1/databases/$DATABASE"] = database(parent("database_id", "not-an-id"))
            }

            "source" -> {
                responses["/v1/data_sources/$SOURCE"] = dataSource(SOURCE, parent("data_source_id", "not-an-id"))
            }

            else -> {
                responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
                responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("data_source_id", "not-an-id"))
            }
        }

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `fails a parent cycle instead of accepting a database ancestor`() {
        responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
        responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("block_id", SYNCED))
        responses["/v1/blocks/$SYNCED"] = blockMetadata(SYNCED, parent("block_id", CONTAINER))

        assertThatThrownBy { fixture.source().fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }

    @Test
    fun `fails when proving ownership exceeds the configured depth`() {
        responses["/v1/databases/$DATABASE"] = database(parent("block_id", CONTAINER))
        responses["/v1/blocks/$CONTAINER"] = blockMetadata(CONTAINER, parent("block_id", SYNCED))
        responses["/v1/blocks/$SYNCED"] = blockMetadata(SYNCED, parent("page_id", ROOT))

        assertThatThrownBy { fixture.source(maxDepth = 1).fetch(reference()) }.isInstanceOf(SourceMappingException::class.java)
    }
}
