package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import java.time.Instant

interface SiteConfigurationRepository {
    fun findCurrent(): SiteConfiguration?

    fun save(
        configuration: SiteConfiguration,
        synchronizedAt: Instant,
    )
}
