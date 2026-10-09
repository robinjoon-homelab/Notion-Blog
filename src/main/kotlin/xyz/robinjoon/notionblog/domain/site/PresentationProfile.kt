package xyz.robinjoon.notionblog.domain.site

import java.util.UUID

@JvmInline
value class PresentationProfileId(
    val value: UUID,
)

@JvmInline
value class PresentationProfileKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "presentation profile key must not be blank" }
    }
}

data class PresentationProfileRef(
    val id: PresentationProfileId,
    val version: Long,
) {
    init {
        require(version >= 0) { "presentation profile version cannot be negative" }
    }
}

data class PresentationProfile(
    val id: PresentationProfileId,
    val key: PresentationProfileKey,
    val version: Long,
    val tokens: PresentationTokens,
    val styleSheets: List<PresentationAssetRef>,
    val scripts: List<PresentationAssetRef>,
) {
    init {
        require(version >= 0) { "presentation profile version cannot be negative" }
    }
}
