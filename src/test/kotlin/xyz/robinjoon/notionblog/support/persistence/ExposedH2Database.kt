package xyz.robinjoon.notionblog.support.persistence

import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostAvailabilityTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSourceBindingTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PresentationProfileAssetTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PresentationProfileTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SiteConfigurationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SyncStateTable
import java.sql.DriverManager
import java.util.UUID

// A fresh in-memory H2 database for one persistence adapter test, so tests share no rows and need no cleanup. The
// deployed Flyway migrations use PostgreSQL-only DDL, so the schema comes from the adapters' Exposed table objects.
class ExposedH2Database : AutoCloseable {
    private val dataSource =
        HikariDataSource().apply {
            jdbcUrl = "jdbc:h2:mem:adapter-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
            maximumPoolSize = 2
        }

    // One attempt, so Exposed reports an expected SQL failure once instead of retrying the statement.
    private val database = Database.connect(dataSource, databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 })

    init {
        transaction(database) {
            SchemaUtils.create(
                PostTable,
                PostSourceBindingTable,
                PostSnapshotTable,
                PostAvailabilityTable,
                PublicationTable,
                PublicationRevisionTable,
                PublicationMemberTable,
                PresentationProfileTable,
                PresentationProfileAssetTable,
                SiteConfigurationTable,
                SyncStateTable,
            )
        }
    }

    // Adapters join the caller's transaction instead of opening one, so each test call supplies a short test-only
    // transaction.
    fun <T> inTransaction(block: () -> T): T = transaction(database) { block() }

    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    // DB_CLOSE_DELAY=-1 keeps the database after the pool closes, so it is dropped through a connection outside the
    // pool. Returning a pooled connection after SHUTDOWN fails because the pool resets the closed connection.
    override fun close() {
        TransactionManager.closeAndUnregister(database)
        dataSource.close()
        DriverManager.getConnection(dataSource.jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { it.execute("SHUTDOWN") }
        }
    }
}
