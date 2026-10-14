package xyz.robinjoon.notionblog.blog.domain.presentation

import xyz.robinjoon.notionblog.domain.AggregateRoot
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

// One version of a trusted presentation profile. Site settings select it by reference.
@AggregateRoot
@ConsistentCopyVisibility
data class PresentationProfile private constructor(
    val reference: PresentationProfileRef,
    val key: PresentationProfileKey,
    val tokens: PresentationTokens,
    val assets: PresentationAssets,
) {
    companion object {
        fun restore(
            reference: PresentationProfileRef,
            key: PresentationProfileKey,
            tokens: PresentationTokens,
            assets: PresentationAssets,
        ): PresentationProfile = PresentationProfile(reference, key, tokens, assets)
    }
}

// Trusted style sheets and scripts in the order the page loads them.
data class PresentationAssets(
    val styleSheets: List<PresentationAssetRef>,
    val scripts: List<PresentationAssetRef>,
)
