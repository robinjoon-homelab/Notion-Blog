package xyz.robinjoon.notionblog.application.port.output.persistence

import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import java.time.Instant

interface SiteConfigurationRepository {
    fun findCurrent(): SiteConfiguration?

    fun save(
        configuration: SiteConfiguration,
        synchronizedAt: Instant,
    )

    fun findProfile(reference: PresentationProfileRef): PresentationProfile?

    fun findCurrentProfile(key: PresentationProfileKey): PresentationProfile?

    fun saveProfile(
        profile: PresentationProfile,
        createdAt: Instant,
    )

    fun activateProfile(reference: PresentationProfileRef)
}
