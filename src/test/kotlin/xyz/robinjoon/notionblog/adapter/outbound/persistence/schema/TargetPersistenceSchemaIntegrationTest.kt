package xyz.robinjoon.notionblog.adapter.outbound.persistence.schema

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import org.testcontainers.containers.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PresentationProfileTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.SiteConfigurationTable
import xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table.SyncStateTable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TargetPersistenceSchemaIntegrationTest {
    private val database = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private val jsonMapper = JsonMapper.builder().build()
    private val exposedDatabase: Database by lazy {
        Database.connect(database.jdbcUrl, driver = "org.postgresql.Driver", user = database.username, password = database.password)
    }

    @BeforeAll
    fun migrate() {
        database.start()
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopDatabase() {
        database.stop()
    }

    @BeforeEach
    fun clearTargetTables() {
        connection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "truncate table site_configuration, publication_member, publication_revision, publication, " +
                        "post_availability, post_snapshot, post_source_binding, post cascade",
                )
            }
            connection
                .prepareStatement(
                    "delete from sync_state where target_kind <> 'SITE_CONFIGURATION' or target_key <> 'singleton'",
                ).use { statement ->
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "delete from presentation_profile_asset where presentation_profile_id <> ?",
                ).use { statement ->
                    statement.setObject(1, defaultProfileId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "delete from presentation_profile where presentation_profile_id <> ?",
                ).use { statement ->
                    statement.setObject(1, defaultProfileId)
                    statement.executeUpdate()
                }
        }
    }

    @Test
    fun `keeps earlier default profiles immutable and activates the Notion-like data view catalog-matched version`() {
        connection { connection ->
            assertSeededProfiles(seedProfiles(connection))
            assertDefaultProfileAssets(connection)
            assertConfigurationSyncDue(connection)
        }
    }

    private fun seedProfiles(connection: Connection): List<SeedProfile> =
        connection
            .prepareStatement(
                "select profile_key, version, token_json, is_current, created_at from presentation_profile " +
                    "where presentation_profile_id = ? order by version",
            ).use { statement ->
                statement.setObject(1, defaultProfileId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                SeedProfile(
                                    rows.getString("profile_key"),
                                    rows.getLong("version"),
                                    rows.getString("token_json"),
                                    rows.getBoolean("is_current"),
                                    rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                                ),
                            )
                        }
                    }
                }
            }

    private fun assertSeededProfiles(profiles: List<SeedProfile>) {
        assertThat(profiles).hasSize(5)
        assertThat(profiles.map(SeedProfile::version)).containsExactly(1, 2, 3, 4, 5)
        assertThat(profiles.map(SeedProfile::current)).containsExactly(false, false, false, false, true)
        assertThat(profiles.map(SeedProfile::createdAt)).containsExactly(
            Instant.parse("2026-08-25T00:00:00Z"),
            Instant.parse("2026-08-27T00:00:00Z"),
            Instant.parse("2026-08-31T00:00:00Z"),
            Instant.parse("2026-08-31T00:00:00Z"),
            Instant.parse("2026-09-10T00:00:00Z"),
        )
        profiles.forEach { profile ->
            assertThat(profile.key).isEqualTo("notion-default")
            assertThat(jsonMapper.readTree(profile.tokenJson)).isEqualTo(
                jsonMapper.readTree("""{"colorMode":"SYSTEM","contentWidth":"STANDARD","density":"COMFORTABLE"}"""),
            )
        }
    }

    private fun assertDefaultProfileAssets(connection: Connection) {
        val assets = profileAssets(connection, 5)
        val configuredIntegrity = configuredAssetIntegrity()

        assertThat(assets).containsExactly(
            SeedAsset(
                "STYLE_SHEET",
                "notion-core",
                1,
                configuredIntegrity.getValue("notion-core" to 1L),
                0,
            ),
            SeedAsset(
                "STYLE_SHEET",
                "katex-styles",
                1,
                configuredIntegrity.getValue("katex-styles" to 1L),
                1,
            ),
            SeedAsset(
                "STYLE_SHEET",
                "notion-enhancements",
                1,
                configuredIntegrity.getValue("notion-enhancements" to 1L),
                2,
            ),
            SeedAsset(
                "STYLE_SHEET",
                "notion-database",
                3,
                configuredIntegrity.getValue("notion-database" to 3L),
                3,
            ),
            SeedAsset(
                "SCRIPT",
                "katex-runtime",
                1,
                configuredIntegrity.getValue("katex-runtime" to 1L),
                0,
            ),
            SeedAsset("SCRIPT", "notion-tabs", 1, configuredIntegrity.getValue("notion-tabs" to 1L), 1),
            SeedAsset("SCRIPT", "notion-math", 1, configuredIntegrity.getValue("notion-math" to 1L), 2),
            SeedAsset("SCRIPT", "notion-database-behavior", 1, configuredIntegrity.getValue("notion-database-behavior" to 1L), 3),
        )
        assertEarlierProfileAssets(connection, assets, configuredIntegrity)
    }

    private fun assertEarlierProfileAssets(
        connection: Connection,
        assets: List<SeedAsset>,
        configuredIntegrity: Map<Pair<String, Long>, String>,
    ) {
        val previousAssets =
            assets.filterNot { it.key == "notion-database-behavior" }.map { asset ->
                if (asset.key == "notion-database") {
                    asset.copy(version = 1, integrity = configuredIntegrity.getValue("notion-database" to 1L))
                } else {
                    asset
                }
            }
        assertThat(profileAssets(connection, 3)).containsExactlyElementsOf(previousAssets)
        assertThat(profileAssets(connection, 4)).containsExactlyElementsOf(
            assets.map { asset ->
                if (asset.key == "notion-database") {
                    asset.copy(version = 2, integrity = configuredIntegrity.getValue("notion-database" to 2L))
                } else {
                    asset
                }
            },
        )
        assertThat(profileAssets(connection, 2)).containsExactlyElementsOf(previousAssets.filterNot { it.key == "notion-database" })
        assertThat(profileAssets(connection, 1)).containsExactly(
            SeedAsset("STYLE_SHEET", "notion-core", 1, configuredIntegrity.getValue("notion-core" to 1L), 0),
            SeedAsset("SCRIPT", "notion-tabs", 1, configuredIntegrity.getValue("notion-tabs" to 1L), 0),
        )
    }

    private fun assertConfigurationSyncDue(connection: Connection) {
        connection
            .prepareStatement(
                "select last_success_at, refresh_after, failure_count, last_error_kind, refresh_after <= now() as is_due " +
                    "from sync_state where target_kind = 'SITE_CONFIGURATION' and target_key = 'singleton'",
            ).executeQuery()
            .use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getObject("last_success_at")).isNull()
                assertThat(rows.getObject("refresh_after", OffsetDateTime::class.java).toInstant())
                    .isEqualTo(Instant.EPOCH)
                assertThat(rows.getInt("failure_count")).isZero()
                assertThat(rows.getObject("last_error_kind")).isNull()
                assertThat(rows.getBoolean("is_due")).isTrue()
                assertThat(rows.next()).isFalse()
            }
    }

    @Test
    fun `upgrades sites using earlier default profiles without changing custom profile selections`() {
        listOf(
            defaultProfileId to 1L,
            defaultProfileId to 2L,
            defaultProfileId to 3L,
            defaultProfileId to 4L,
            UUID.randomUUID() to 1L,
        ).forEach { (profileId, previousVersion) ->
            val schema = "profile_upgrade_" + UUID.randomUUID().toString().replace("-", "")
            migrateSchema(schema, "5")
            connection { connection ->
                connection.createStatement().use { it.execute("set search_path to $schema") }
                val publicationId = UUID.randomUUID()
                insertPublication(connection, publicationId)
                insertCustomProfileSelection(connection, profileId, previousVersion)
                connection.insertSiteConfiguration(1.toShort(), publicationId, profileId, previousVersion)

                migrateSchema(schema)

                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "select presentation_profile_id, presentation_profile_version from site_configuration",
                        ).use { rows ->
                            assertThat(rows.next()).isTrue()
                            assertThat(rows.getObject("presentation_profile_id", UUID::class.java)).isEqualTo(profileId)
                            assertThat(rows.getLong("presentation_profile_version"))
                                .isEqualTo(if (profileId == defaultProfileId) 5L else previousVersion)
                            assertThat(rows.next()).isFalse()
                        }
                }
                assertCustomProfileUnchanged(connection, profileId, previousVersion)
            }
        }
    }

    private fun insertCustomProfileSelection(
        connection: Connection,
        profileId: UUID,
        previousVersion: Long,
    ) {
        if (profileId == defaultProfileId) return
        connection.insertProfile(profileId, "custom-theme", previousVersion, true)
        connection
            .prepareStatement(
                "insert into presentation_profile_asset " +
                    "(presentation_profile_id, presentation_profile_version, asset_kind, asset_key, asset_version, integrity, position) " +
                    "values (?, ?, 'STYLE_SHEET', 'notion-database', 1, ?, 0)",
            ).use { statement ->
                statement.setObject(1, profileId)
                statement.setLong(2, previousVersion)
                statement.setString(3, configuredAssetIntegrity().getValue("notion-database" to 1L))
                statement.executeUpdate()
            }
    }

    private fun assertCustomProfileUnchanged(
        connection: Connection,
        profileId: UUID,
        previousVersion: Long,
    ) {
        if (profileId == defaultProfileId) return
        connection
            .prepareStatement(
                "select is_current, token_json from presentation_profile where presentation_profile_id = ? and version = ?",
            ).use { statement ->
                statement.setObject(1, profileId)
                statement.setLong(2, previousVersion)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getBoolean("is_current")).isTrue()
                    assertThat(jsonMapper.readTree(rows.getString("token_json"))).isEqualTo(jsonMapper.readTree("{}"))
                }
            }
        connection
            .prepareStatement(
                "select asset_kind, asset_key, asset_version, integrity, position from presentation_profile_asset " +
                    "where presentation_profile_id = ? and presentation_profile_version = ?",
            ).use { statement ->
                statement.setObject(1, profileId)
                statement.setLong(2, previousVersion)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("asset_kind")).isEqualTo("STYLE_SHEET")
                    assertThat(rows.getString("asset_key")).isEqualTo("notion-database")
                    assertThat(rows.getLong("asset_version")).isEqualTo(1L)
                    assertThat(rows.getString("integrity")).isEqualTo(
                        configuredAssetIntegrity().getValue("notion-database" to 1L),
                    )
                    assertThat(rows.getInt("position")).isZero()
                    assertThat(rows.next()).isFalse()
                }
            }
    }

    @Test
    fun `migrates every target table with PostgreSQL types and matching Exposed declarations`() {
        connection { connection ->
            val tables =
                connection
                    .prepareStatement(
                        "select table_name from information_schema.tables where table_schema = 'public' and table_name in " +
                            "('post', 'post_source_binding', 'post_snapshot', 'post_availability', 'publication', " +
                            "'publication_revision', 'publication_member', 'site_configuration', 'presentation_profile', " +
                            "'presentation_profile_asset', 'sync_state')",
                    ).executeQuery()
                    .use { rows ->
                        buildSet {
                            while (rows.next()) add(rows.getString("table_name"))
                        }
                    }

            assertThat(tables).containsExactlyInAnyOrder(
                "post",
                "post_source_binding",
                "post_snapshot",
                "post_availability",
                "publication",
                "publication_revision",
                "publication_member",
                "site_configuration",
                "presentation_profile",
                "presentation_profile_asset",
                "sync_state",
            )
            assertThat(columnType(connection, "post", "post_id")).isEqualTo("uuid")
            assertThat(columnType(connection, "post_snapshot", "snapshot_json")).isEqualTo("jsonb")
            assertThat(columnType(connection, "site_configuration", "site_id")).isEqualTo("smallint")
            assertThat(columnType(connection, "publication_member", "depth")).isEqualTo("integer")
            assertThat(columnType(connection, "sync_state", "refresh_after")).isEqualTo("timestamp with time zone")

            transaction(exposedDatabase) {
                assertThat(PostTable.tableName).isEqualTo("post")
                assertThat(PostTable.postId.columnType.sqlType()).isEqualToIgnoringCase("UUID")
                assertThat(PostSnapshotTable.snapshotJson.columnType.sqlType()).isEqualToIgnoringCase("JSONB")
                assertThat(PublicationMemberTable.depth.columnType.sqlType()).isEqualToIgnoringCase("INT")
                assertThat(SiteConfigurationTable.siteId.columnType.sqlType()).isEqualToIgnoringCase("SMALLINT")
                assertThat(PresentationProfileTable.isCurrent.columnType.sqlType()).isEqualToIgnoringCase("BOOLEAN")
                assertThat(SyncStateTable.refreshAfter.columnType.sqlType()).isEqualToIgnoringCase("TIMESTAMP WITH TIME ZONE")
            }
        }
    }

    @Test
    fun `does not create legacy PoC tables on a clean database`() {
        connection { connection ->
            val legacyTables =
                connection
                    .prepareStatement(
                        "select table_name from information_schema.tables where table_schema = 'public' and table_name in " +
                            "('notion_page', 'site_settings', 'page_snapshot', 'page_route')",
                    ).executeQuery()
                    .use { rows ->
                        buildSet {
                            while (rows.next()) add(rows.getString("table_name"))
                        }
                    }

            assertThat(legacyTables).isEmpty()
        }
    }

    @Test
    fun `enforces source binding publication ownership member parent and root constraints`() {
        connection { connection ->
            val firstPost = UUID.randomUUID()
            val secondPost = UUID.randomUUID()
            val thirdPost = UUID.randomUUID()
            val firstPublication = UUID.randomUUID()
            val secondPublication = UUID.randomUUID()
            val firstRevision = UUID.randomUUID()
            val secondRevision = UUID.randomUUID()

            insertPost(connection, firstPost)
            insertPost(connection, secondPost)
            insertPost(connection, thirdPost)
            insertBinding(connection, "notion-main", "external-1", firstPost)
            assertThatThrownBy { insertBinding(connection, "notion-main", "external-1", secondPost) }
                .hasMessageContaining("post_source_binding_pkey")
            assertThatThrownBy { insertBinding(connection, "notion-main", "external-2", firstPost) }
                .hasMessageContaining("post_source_binding_post_id_key")

            insertPublication(connection, firstPublication)
            insertPublication(connection, secondPublication)
            connection.insertRevision(firstRevision, firstPublication, "STAGING")
            connection.insertRevision(secondRevision, secondPublication, "STAGING")
            connection.insertRevision(UUID.randomUUID(), firstPublication, "ACTIVE", activated = true)
            assertThatThrownBy { connection.insertRevision(UUID.randomUUID(), firstPublication, "ACTIVE", activated = true) }
                .hasMessageContaining("publication_revision_one_active_per_publication")
            activatePublication(connection, firstPublication, firstPost, firstRevision)
            assertThatThrownBy { activatePublication(connection, secondPublication, secondPost, firstRevision) }
                .hasMessageContaining("publication_active_revision_ownership_fkey")

            connection.insertMember(firstRevision, firstPost, null, 0)
            assertThatThrownBy { connection.insertMember(firstRevision, secondPost, null, 0) }
                .hasMessageContaining("publication_member_one_root_per_revision")
            assertThatThrownBy { connection.insertMember(firstRevision, secondPost, thirdPost, 1) }
                .hasMessageContaining("publication_member_parent_in_same_revision_fkey")
            assertThatThrownBy { connection.insertMember(firstRevision, secondPost, firstPost, -1) }
                .hasMessageContaining("publication_member")
        }
    }

    @Test
    fun `enforces profile version references current profile uniqueness and sync enum values`() {
        connection { connection ->
            val publicationId = UUID.randomUUID()
            val profileId = UUID.randomUUID()

            insertPublication(connection, publicationId)
            connection.insertProfile(profileId, "default", 1, true)
            assertThatThrownBy { connection.insertProfile(UUID.randomUUID(), "default", 2, true) }
                .hasMessageContaining("presentation_profile_one_current_per_key")
            connection.insertProfile(profileId, "default", 2, false)
            assertThatThrownBy { connection.insertSiteConfiguration(1.toShort(), publicationId, profileId, 99) }
                .hasMessageContaining("site_configuration_presentation_profile_fkey")
            connection.insertSiteConfiguration(1.toShort(), publicationId, profileId, 1)
            assertThatThrownBy { connection.insertSiteConfiguration(2.toShort(), publicationId, profileId, 2) }
                .hasMessageContaining("site_configuration_singleton_check")

            assertThatThrownBy {
                connection
                    .prepareStatement(
                        "insert into sync_state (target_kind, target_key, refresh_after, failure_count, last_error_kind) " +
                            "values ('POST', 'post-1', now(), 1, 'HTTP_429')",
                    ).use { it.executeUpdate() }
            }.hasMessageContaining("sync_state_last_error_kind_check")
            assertThatThrownBy {
                connection
                    .prepareStatement(
                        "insert into sync_state (target_kind, target_key, refresh_after, failure_count, last_error_kind) " +
                            "values ('POST', 'post-2', now(), 0, 'MAPPING')",
                    ).use { it.executeUpdate() }
            }.hasMessageContaining("sync_state_failure_error_pair_check")
            assertThatThrownBy {
                connection
                    .prepareStatement(
                        "insert into post_availability (post_id, status, confirmed_at) values (?, 'private', now())",
                    ).use { statement ->
                        statement.setObject(1, UUID.randomUUID())
                        statement.executeUpdate()
                    }
            }.hasMessageContaining("post_availability_status_check")
        }
    }

    private fun profileAssets(
        connection: Connection,
        version: Long,
    ): List<SeedAsset> =
        connection
            .prepareStatement(
                "select asset_kind, asset_key, asset_version, integrity, position from presentation_profile_asset " +
                    "where presentation_profile_id = ? and presentation_profile_version = ? " +
                    "order by case asset_kind when 'STYLE_SHEET' then 0 else 1 end, position",
            ).use { statement ->
                statement.setObject(1, defaultProfileId)
                statement.setLong(2, version)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                SeedAsset(
                                    rows.getString("asset_kind"),
                                    rows.getString("asset_key"),
                                    rows.getLong("asset_version"),
                                    rows.getString("integrity"),
                                    rows.getInt("position"),
                                ),
                            )
                        }
                    }
                }
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

    private fun columnType(
        connection: Connection,
        table: String,
        column: String,
    ): String =
        connection
            .prepareStatement(
                "select data_type from information_schema.columns where table_schema = 'public' and table_name = ? and column_name = ?",
            ).use { statement ->
                statement.setString(1, table)
                statement.setString(2, column)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "column $table.$column was not found" }
                    rows.getString("data_type")
                }
            }

    private fun insertPost(
        connection: Connection,
        postId: UUID,
    ) {
        connection
            .prepareStatement(
                "insert into post (post_id, title, created_at, updated_at) values (?, 'Post', now(), now())",
            ).use { statement ->
                statement.setObject(1, postId)
                statement.executeUpdate()
            }
    }

    private fun insertBinding(
        connection: Connection,
        sourceId: String,
        externalId: String,
        postId: UUID,
    ) {
        connection.prepareStatement("insert into post_source_binding (source_id, external_id, post_id) values (?, ?, ?)").use { statement ->
            statement.setString(1, sourceId)
            statement.setString(2, externalId)
            statement.setObject(3, postId)
            statement.executeUpdate()
        }
    }

    private fun insertPublication(
        connection: Connection,
        publicationId: UUID,
    ) {
        connection.prepareStatement("insert into publication (publication_id) values (?)").use { statement ->
            statement.setObject(1, publicationId)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertRevision(
        revisionId: UUID,
        publicationId: UUID,
        state: String,
        activated: Boolean = false,
    ) {
        prepareStatement(
            "insert into publication_revision (revision_id, publication_id, state, started_at, activated_at) " +
                "values (?, ?, ?, now(), ?) ",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.setObject(2, publicationId)
            statement.setString(3, state)
            if (activated) {
                statement.setObject(4, OffsetDateTime.now())
            } else {
                statement.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE)
            }
            statement.executeUpdate()
        }
    }

    private fun activatePublication(
        connection: Connection,
        publicationId: UUID,
        rootPostId: UUID,
        revisionId: UUID,
    ) {
        connection
            .prepareStatement(
                "update publication set root_post_id = ?, active_revision_id = ? where publication_id = ?",
            ).use { statement ->
                statement.setObject(1, rootPostId)
                statement.setObject(2, revisionId)
                statement.setObject(3, publicationId)
                statement.executeUpdate()
            }
    }

    private fun Connection.insertMember(
        revisionId: UUID,
        postId: UUID,
        parentPostId: UUID?,
        depth: Int,
    ) {
        prepareStatement(
            "insert into publication_member (revision_id, post_id, parent_post_id, depth) values (?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.setObject(2, postId)
            statement.setObject(3, parentPostId)
            statement.setInt(4, depth)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertProfile(
        profileId: UUID,
        key: String,
        version: Long,
        isCurrent: Boolean,
    ) {
        prepareStatement(
            "insert into presentation_profile (presentation_profile_id, profile_key, version, token_json, is_current, created_at) " +
                "values (?, ?, ?, '{}'::jsonb, ?, now())",
        ).use { statement ->
            statement.setObject(1, profileId)
            statement.setString(2, key)
            statement.setLong(3, version)
            statement.setBoolean(4, isCurrent)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertSiteConfiguration(
        siteId: Short,
        publicationId: UUID,
        profileId: UUID,
        profileVersion: Long,
    ) {
        prepareStatement(
            "insert into site_configuration (site_id, publication_id, root_source_id, root_external_id, metadata_json, " +
                "presentation_profile_id, presentation_profile_version, synced_at) values (?, ?, 'notion-main', 'root', " +
                "'{}'::jsonb, ?, ?, now())",
        ).use { statement ->
            statement.setShort(1, siteId)
            statement.setObject(2, publicationId)
            statement.setObject(3, profileId)
            statement.setLong(4, profileVersion)
            statement.executeUpdate()
        }
    }

    private fun connection(block: (Connection) -> Unit) {
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use(block)
    }

    private fun configuredAssetIntegrity(): Map<Pair<String, Long>, String> {
        val factory = YamlPropertiesFactoryBean()
        factory.setResources(ClassPathResource("application.yml"))
        val properties = requireNotNull(factory.getObject()) { "application.yml properties are missing" }
        return generateSequence(0) { it + 1 }
            .map { index ->
                val prefix = "blog.presentation.assets[$index]"
                val key = properties.getProperty("$prefix.key") ?: return@map null
                (key to properties.getProperty("$prefix.version").toLong()) to properties.getProperty("$prefix.integrity")
            }.takeWhile { it != null }
            .filterNotNull()
            .toMap()
    }

    private data class SeedProfile(
        val key: String,
        val version: Long,
        val tokenJson: String,
        val current: Boolean,
        val createdAt: Instant,
    )

    private data class SeedAsset(
        val kind: String,
        val key: String,
        val version: Long,
        val integrity: String,
        val position: Int,
    )

    private companion object {
        val defaultProfileId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
