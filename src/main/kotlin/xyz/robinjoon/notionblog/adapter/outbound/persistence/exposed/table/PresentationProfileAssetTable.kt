package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object PresentationProfileAssetTable : Table("presentation_profile_asset") {
    val presentationProfileId = javaUUID("presentation_profile_id")
    val presentationProfileVersion = long("presentation_profile_version")
    val assetKind = text("asset_kind")
    val assetKey = text("asset_key")
    val assetVersion = long("asset_version")
    val integrity = text("integrity")
    val position = integer("position")

    override val primaryKey = PrimaryKey(presentationProfileId, presentationProfileVersion, assetKind, position)
}
