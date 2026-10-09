package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PublicationRevisionTable : Table("publication_revision") {
    val revisionId = javaUUID("revision_id")
    val publicationId = javaUUID("publication_id").references(PublicationTable.publicationId)
    val state = text("state")
    val startedAt = timestampWithTimeZone("started_at")
    val activatedAt = timestampWithTimeZone("activated_at").nullable()

    override val primaryKey = PrimaryKey(revisionId)

    init {
        uniqueIndex(publicationId, revisionId)
    }
}
