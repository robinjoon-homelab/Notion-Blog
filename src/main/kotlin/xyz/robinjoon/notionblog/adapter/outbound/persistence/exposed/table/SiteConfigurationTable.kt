package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

object SiteConfigurationTable : Table("site_configuration") {
    val siteId = short("site_id")
    val publicationId = javaUUID("publication_id").references(PublicationTable.publicationId).uniqueIndex()
    val rootSourceId = text("root_source_id")
    val rootExternalId = text("root_external_id")
    val headerSourceId = text("header_source_id").nullable()
    val headerExternalId = text("header_external_id").nullable()
    val footerSourceId = text("footer_source_id").nullable()
    val footerExternalId = text("footer_external_id").nullable()
    val metadataJson = jsonb("metadata_json", { value: String -> value }, { value -> value })
    val presentationProfileId = javaUUID("presentation_profile_id")
    val presentationProfileVersion = long("presentation_profile_version")
    val syncedAt = timestampWithTimeZone("synced_at")

    override val primaryKey = PrimaryKey(siteId)
}
