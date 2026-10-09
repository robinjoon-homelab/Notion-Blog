package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

object PresentationProfileTable : Table("presentation_profile") {
    val presentationProfileId = javaUUID("presentation_profile_id")
    val profileKey = text("profile_key")
    val version = long("version")
    val tokenJson = jsonb("token_json", { value: String -> value }, { value -> value })
    val isCurrent = bool("is_current")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(presentationProfileId, version)

    init {
        uniqueIndex(profileKey, version)
    }
}
