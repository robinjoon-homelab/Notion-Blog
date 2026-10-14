package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration

data class AppliedSiteConfiguration(
    val configuration: SiteConfiguration,
    val rootChanged: Boolean,
)
