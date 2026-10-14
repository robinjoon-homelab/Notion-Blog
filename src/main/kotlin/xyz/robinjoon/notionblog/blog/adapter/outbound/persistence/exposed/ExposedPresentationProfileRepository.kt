package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PresentationProfileAssetTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PresentationProfileTable
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssets
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationColorMode
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationContentWidth
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationDensity
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationTokens
import java.time.Instant
import java.time.ZoneOffset

class ExposedPresentationProfileRepository : PresentationProfileRepository {
    private val jsonMapper = JsonMapper.builder().build()

    override fun findProfile(reference: PresentationProfileRef): PresentationProfile? =
        PresentationProfileTable
            .selectAll()
            .where {
                (PresentationProfileTable.presentationProfileId eq reference.id.value) and
                    (PresentationProfileTable.version eq reference.version)
            }.singleOrNull()
            ?.toPresentationProfile()

    override fun findCurrentProfile(key: PresentationProfileKey): PresentationProfile? =
        PresentationProfileTable
            .selectAll()
            .where {
                (PresentationProfileTable.profileKey eq key.value) and
                    (PresentationProfileTable.isCurrent eq true)
            }.singleOrNull()
            ?.toPresentationProfile()

    override fun saveProfile(
        profile: PresentationProfile,
        createdAt: Instant,
    ) {
        PresentationProfileTable.insert {
            it[presentationProfileId] = profile.reference.id.value
            it[profileKey] = profile.key.value
            it[version] = profile.reference.version
            it[tokenJson] = encodeTokens(profile.tokens)
            it[isCurrent] = false
            it[PresentationProfileTable.createdAt] = createdAt.asOffsetDateTime()
        }
        saveAssets(profile, AssetKind.STYLE_SHEET, profile.assets.styleSheets)
        saveAssets(profile, AssetKind.SCRIPT, profile.assets.scripts)
    }

    override fun activateProfile(reference: PresentationProfileRef) {
        val row =
            PresentationProfileTable
                .selectAll()
                .where {
                    (PresentationProfileTable.presentationProfileId eq reference.id.value) and
                        (PresentationProfileTable.version eq reference.version)
                }.singleOrNull()
                ?: error("presentation profile ${reference.id.value} version ${reference.version} does not exist")

        PresentationProfileTable.update({ PresentationProfileTable.profileKey eq row[PresentationProfileTable.profileKey] }) {
            it[isCurrent] = false
        }
        check(
            PresentationProfileTable.update({
                (PresentationProfileTable.presentationProfileId eq reference.id.value) and
                    (PresentationProfileTable.version eq reference.version)
            }) {
                it[isCurrent] = true
            } == 1,
        ) { "presentation profile ${reference.id.value} version ${reference.version} was not activated" }
    }

    private fun saveAssets(
        profile: PresentationProfile,
        kind: AssetKind,
        assets: List<PresentationAssetRef>,
    ) {
        assets.forEachIndexed { position, asset ->
            PresentationProfileAssetTable.insert {
                it[presentationProfileId] = profile.reference.id.value
                it[presentationProfileVersion] = profile.reference.version
                it[assetKind] = kind.name
                it[assetKey] = asset.key
                it[assetVersion] = asset.version
                it[integrity] = asset.integrity
                it[PresentationProfileAssetTable.position] = position
            }
        }
    }

    private fun ResultRow.toPresentationProfile(): PresentationProfile {
        val reference =
            PresentationProfileRef(
                PresentationProfileId(this[PresentationProfileTable.presentationProfileId]),
                this[PresentationProfileTable.version],
            )
        return PresentationProfile.restore(
            reference = reference,
            key = PresentationProfileKey(this[PresentationProfileTable.profileKey]),
            tokens = decodeTokens(this[PresentationProfileTable.tokenJson]),
            assets =
                PresentationAssets(
                    styleSheets = findAssets(reference, AssetKind.STYLE_SHEET),
                    scripts = findAssets(reference, AssetKind.SCRIPT),
                ),
        )
    }

    private fun findAssets(
        reference: PresentationProfileRef,
        kind: AssetKind,
    ): List<PresentationAssetRef> =
        PresentationProfileAssetTable
            .selectAll()
            .where {
                (PresentationProfileAssetTable.presentationProfileId eq reference.id.value) and
                    (PresentationProfileAssetTable.presentationProfileVersion eq reference.version) and
                    (PresentationProfileAssetTable.assetKind eq kind.name)
            }.orderBy(PresentationProfileAssetTable.position to SortOrder.ASC)
            .map { row ->
                PresentationAssetRef(
                    row[PresentationProfileAssetTable.assetKey],
                    row[PresentationProfileAssetTable.assetVersion],
                    row[PresentationProfileAssetTable.integrity],
                )
            }

    private fun encodeTokens(tokens: PresentationTokens): String =
        jsonMapper
            .createObjectNode()
            .apply {
                put("colorMode", tokens.colorMode.name)
                put("contentWidth", tokens.contentWidth.name)
                put("density", tokens.density.name)
            }.toString()

    private fun decodeTokens(value: String): PresentationTokens {
        val node = jsonMapper.readTree(value)
        return PresentationTokens(
            colorMode = PresentationColorMode.valueOf(node.requiredText("colorMode")),
            contentWidth = PresentationContentWidth.valueOf(node.requiredText("contentWidth")),
            density = PresentationDensity.valueOf(node.requiredText("density")),
        )
    }

    private fun JsonNode.requiredText(field: String): String =
        get(field)
            ?.takeUnless(JsonNode::isNull)
            ?.asString()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("missing or blank JSON field: $field")

    private fun Instant.asOffsetDateTime() = atOffset(ZoneOffset.UTC)

    private enum class AssetKind {
        STYLE_SHEET,
        SCRIPT,
    }
}
