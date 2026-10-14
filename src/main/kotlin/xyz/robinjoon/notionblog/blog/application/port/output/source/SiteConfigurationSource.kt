package xyz.robinjoon.notionblog.blog.application.port.output.source

import xyz.robinjoon.notionblog.blog.application.model.ImportedSiteConfiguration

interface SiteConfigurationSource {
    fun fetch(): ImportedSiteConfiguration
}
