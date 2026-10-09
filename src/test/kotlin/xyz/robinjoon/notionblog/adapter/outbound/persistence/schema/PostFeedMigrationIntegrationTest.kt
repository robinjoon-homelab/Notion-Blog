package xyz.robinjoon.notionblog.adapter.outbound.persistence.schema

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostFeedMigrationIntegrationTest {
    private val database = PostgreSQLContainer<Nothing>("postgres:16-alpine")

    @BeforeAll
    fun startDatabase() {
        database.start()
    }

    @AfterAll
    fun stopDatabase() {
        database.stop()
    }

    @Test
    fun `backfills published and unpublished snapshots from captured time while leaving never published identities unset`() {
        val schema = schemaAtVersionSix()
        val publishedPostId = UUID.randomUUID()
        val unpublishedPostId = UUID.randomUUID()
        val identityPostId = UUID.randomUUID()
        val publishedCapturedAt = Instant.parse("2026-09-01T04:05:06.123456Z")
        val unpublishedCapturedAt = Instant.parse("2026-09-02T07:08:09.654321Z")
        connection(schema) { connection ->
            insertLegacyPost(connection, publishedPostId, "PUBLISHED", publishedCapturedAt)
            insertLegacyPost(connection, unpublishedPostId, "UNPUBLISHED", unpublishedCapturedAt)
            insertLegacyPost(connection, identityPostId, "UNPUBLISHED")
        }

        migrateSchema(schema)

        connection(schema) { connection ->
            val firstPublishedTimes =
                connection.createStatement().use { statement ->
                    statement.executeQuery("select post_id, first_published_at from post").use { rows ->
                        buildMap {
                            while (rows.next()) {
                                put(
                                    rows.getObject("post_id", UUID::class.java),
                                    rows.getObject("first_published_at", OffsetDateTime::class.java)?.toInstant(),
                                )
                            }
                        }
                    }
                }

            assertThat(firstPublishedTimes)
                .hasSize(3)
                .containsEntry(publishedPostId, publishedCapturedAt)
                .containsEntry(unpublishedPostId, unpublishedCapturedAt)
                .containsEntry(identityPostId, null)
        }
    }

    @Test
    fun `adds nullable publication timestamp and a partial B tree index ordered for the feed`() {
        val schema = schemaAtVersionSix()

        migrateSchema(schema)

        connection(schema) { connection ->
            connection
                .prepareStatement(
                    "select data_type, is_nullable from information_schema.columns " +
                        "where table_schema = ? and table_name = 'post' and column_name = 'first_published_at'",
                ).use { statement ->
                    statement.setString(1, schema)
                    statement.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString("data_type")).isEqualTo("timestamp with time zone")
                        assertThat(rows.getString("is_nullable")).isEqualTo("YES")
                        assertThat(rows.next()).isFalse()
                    }
                }
            connection
                .prepareStatement(
                    "select access_method.amname, index_info.indnkeyatts, index_info.indnatts, index_info.indisvalid, " +
                        "pg_get_indexdef(index_info.indexrelid, 1, true) as first_key, " +
                        "pg_get_indexdef(index_info.indexrelid, 2, true) as second_key, " +
                        "pg_index_column_has_property(index_info.indexrelid, 1, 'desc') as first_descending, " +
                        "pg_index_column_has_property(index_info.indexrelid, 2, 'asc') as second_ascending, " +
                        "pg_get_expr(index_info.indpred, index_info.indrelid) as predicate " +
                        "from pg_index index_info " +
                        "join pg_class table_info on table_info.oid = index_info.indrelid " +
                        "join pg_namespace schema_info on schema_info.oid = table_info.relnamespace " +
                        "join pg_class index_table on index_table.oid = index_info.indexrelid " +
                        "join pg_am access_method on access_method.oid = index_table.relam " +
                        "where schema_info.nspname = ? and table_info.relname = 'post' and index_info.indpred is not null",
                ).use { statement ->
                    statement.setString(1, schema)
                    statement.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString("amname")).isEqualTo("btree")
                        assertThat(rows.getInt("indnkeyatts")).isEqualTo(2)
                        assertThat(rows.getInt("indnatts")).isEqualTo(2)
                        assertThat(rows.getBoolean("indisvalid")).isTrue()
                        assertThat(rows.getString("first_key")).isEqualTo("first_published_at")
                        assertThat(rows.getString("second_key")).isEqualTo("post_id")
                        assertThat(rows.getBoolean("first_descending")).isTrue()
                        assertThat(rows.getBoolean("second_ascending")).isTrue()
                        assertThat(rows.getString("predicate")).isEqualTo("(first_published_at IS NOT NULL)")
                        assertThat(rows.next()).isFalse()
                    }
                }
        }
    }

    private fun schemaAtVersionSix(): String {
        val schema = "post_feed_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        migrateSchema(schema, "6")
        return schema
    }

    private fun migrateSchema(
        schema: String,
        target: String = "latest",
    ) {
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
            .target(target)
            .load()
            .migrate()
    }

    private fun insertLegacyPost(
        connection: Connection,
        postId: UUID,
        status: String,
        capturedAt: Instant? = null,
    ) {
        connection
            .prepareStatement(
                "insert into post (post_id, title, created_at, updated_at) " +
                    "values (?, 'Legacy post', '2026-08-01T00:00:00Z', '2026-09-03T00:00:00Z')",
            ).use { statement ->
                statement.setObject(1, postId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                "insert into post_availability (post_id, status, confirmed_at) values (?, ?, '2026-09-04T00:00:00Z')",
            ).use { statement ->
                statement.setObject(1, postId)
                statement.setString(2, status)
                statement.executeUpdate()
            }
        if (capturedAt != null) {
            connection
                .prepareStatement(
                    "insert into post_snapshot (post_id, snapshot_json, source_revision, captured_at) " +
                        "values (?, '{\"schemaVersion\":1,\"kind\":\"block_tree_snapshot\",\"blocks\":[]}'::jsonb, 'legacy-revision', ?)",
                ).use { statement ->
                    statement.setObject(1, postId)
                    statement.setObject(2, capturedAt.atOffset(ZoneOffset.UTC))
                    statement.executeUpdate()
                }
        }
    }

    private fun <T> connection(
        schema: String,
        action: (Connection) -> T,
    ): T =
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
            connection.createStatement().use { it.execute("set search_path to $schema") }
            action(connection)
        }
}
