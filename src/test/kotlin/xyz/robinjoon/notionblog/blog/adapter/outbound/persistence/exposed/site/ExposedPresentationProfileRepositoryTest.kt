package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.site

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPresentationProfileRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PresentationProfileTable
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
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExposedPresentationProfileRepositoryTest {
    private val database = ExposedH2Database()
    private val profiles = ExposedPresentationProfileRepository()

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `round trips a profile with normalized tokens and ordered assets`() =
        inTransaction {
            val profile = profile(version = 4)
            val createdAt = Instant.parse("2026-08-25T01:02:03Z")

            profiles.saveProfile(profile, createdAt)

            assertThat(profiles.findProfile(profile.reference)).isEqualTo(profile)
            val mapper = JsonMapper.builder().build()
            val tokenJson = PresentationProfileTable.selectAll().single()[PresentationProfileTable.tokenJson]
            assertThat(mapper.readTree(tokenJson)).isEqualTo(
                mapper.readTree("""{"colorMode":"DARK","contentWidth":"WIDE","density":"COMPACT"}"""),
            )
            assertThat(tokenJson).doesNotContain("xyz.", "@class", "css", "javascript", "head")
            assertThat(
                PresentationProfileTable.selectAll().single()[PresentationProfileTable.createdAt],
            ).isEqualTo(createdAt.atOffset(ZoneOffset.UTC))
        }

    @Test
    fun `keeps profile versions immutable and resolves only an explicitly activated current version`() =
        inTransaction {
            val profileId = PresentationProfileId(UUID.randomUUID())
            val key = PresentationProfileKey("notion-like")
            val first = profile(profileId, key, 1)
            val second = profile(profileId, key, 2)

            profiles.saveProfile(first, Instant.parse("2026-08-25T01:02:03Z"))
            profiles.saveProfile(second, Instant.parse("2026-08-25T01:02:04Z"))

            assertThat(profiles.findCurrentProfile(key)).isNull()
            profiles.activateProfile(first.reference)
            assertThat(profiles.findCurrentProfile(key)).isEqualTo(first)
            profiles.activateProfile(second.reference)
            assertThat(profiles.findCurrentProfile(key)).isEqualTo(second)
            assertThat(profiles.findProfile(first.reference)).isEqualTo(first)
        }

    private fun profile(
        id: PresentationProfileId = PresentationProfileId(UUID.randomUUID()),
        key: PresentationProfileKey = PresentationProfileKey("notion-like"),
        version: Long,
    ): PresentationProfile =
        PresentationProfile.restore(
            reference = PresentationProfileRef(id, version),
            key = key,
            tokens = PresentationTokens(PresentationColorMode.DARK, PresentationContentWidth.WIDE, PresentationDensity.COMPACT),
            assets =
                PresentationAssets(
                    styleSheets =
                        listOf(
                            PresentationAssetRef("base", 1, "sha256-base"),
                            PresentationAssetRef("theme", 3, "sha256-theme"),
                        ),
                    scripts =
                        listOf(
                            PresentationAssetRef("navigation", 2, "sha256-navigation"),
                            PresentationAssetRef("analytics", 5, "sha256-analytics"),
                        ),
                ),
        )

    private fun inTransaction(block: () -> Unit) {
        database.inTransaction(block)
    }
}
