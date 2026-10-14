package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import java.time.Instant

interface PresentationProfileRepository {
    fun findProfile(reference: PresentationProfileRef): PresentationProfile?

    fun findCurrentProfile(key: PresentationProfileKey): PresentationProfile?

    fun saveProfile(
        profile: PresentationProfile,
        createdAt: Instant,
    )

    fun activateProfile(reference: PresentationProfileRef)
}
